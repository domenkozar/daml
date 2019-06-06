// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.sandbox.stores.ledger

import java.util.concurrent.{CompletableFuture, CompletionStage}

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.daml.ledger.participant.state.index.v2._
import com.daml.ledger.participant.state.v1.{
  PartyAllocationResult,
  SubmittedTransaction,
  UploadDarResult
}
import com.daml.ledger.participant.state.{v1 => ParticipantState}
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml.lf.data.Ref.{LedgerString, PackageId, Party, TransactionIdString}
import com.digitalasset.daml.lf.lfpackage.Ast
import com.digitalasset.daml.lf.transaction.Node.GlobalKey
import com.digitalasset.daml.lf.value.Value
import com.digitalasset.daml.lf.value.Value.{AbsoluteContractId, ContractInst}
import com.digitalasset.daml_lf.DamlLf.Archive
import com.digitalasset.ledger.api.domain
import com.digitalasset.ledger.api.domain.CompletionEvent.{
  Checkpoint,
  CommandAccepted,
  CommandRejected
}
import com.digitalasset.ledger.api.domain.{LedgerId, _}
import com.digitalasset.platform.common.util.{DirectExecutionContext => DEC}
import com.digitalasset.platform.participant.util.EventFilter
import com.digitalasset.platform.sandbox.damle.SandboxPackageStore
import com.digitalasset.platform.sandbox.stores.ActiveContracts
import com.digitalasset.platform.server.api.validation.ErrorFactories
import com.digitalasset.platform.services.time.TimeModel
import scalaz.syntax.tag._

import scala.compat.java8.FutureConverters
import scala.concurrent.{Future, Promise}

class SandboxIndexAndWriteService(
    ledger: Ledger,
    timeModel: TimeModel,
    packageStore: SandboxPackageStore,
    contractStore: ContractStore)(implicit mat: Materializer)
    extends ParticipantState.WriteService
    with IndexService {
  override def getLedgerId(): Future[LedgerId] = Future.successful(ledger.ledgerId)

  override def getLedgerConfiguration(): Source[LedgerConfiguration, NotUsed] =
    Source
      .single(LedgerConfiguration(timeModel.minTtl, timeModel.maxTtl))
      .concat(Source.fromFuture(Promise[LedgerConfiguration]().future)) // we should keep the stream open!

  override def getActiveContractSetSnapshot(
      filter: TransactionFilter): Future[ActiveContractSetSnapshot] =
    ledger
      .snapshot()
      .map {
        case LedgerSnapshot(offset, acsStream) =>
          ActiveContractSetSnapshot(
            LedgerOffset.Absolute(LedgerString.fromLong(offset)),
            acsStream
              .mapConcat {
                case (cId, ac) =>
                  val create = toUpdateEvent(cId, ac)
                  EventFilter
                    .byTemplates(filter)
                    .filterActiveContractWitnesses(create)
                    .map(create => ac.workflowId.map(domain.WorkflowId(_)) -> create)
                    .toList
              }
          )
      }(mat.executionContext)

  private def toUpdateEvent(
      cId: Value.AbsoluteContractId,
      ac: ActiveContracts.ActiveContract): AcsUpdateEvent.Create =
    AcsUpdateEvent.Create(
      // we use absolute contract ids as event ids throughout the sandbox
      domain.TransactionId(ac.transactionId),
      EventId(cId.coid),
      cId,
      ac.contract.template,
      ac.contract.arg,
      ac.witnesses
    )

  private def getTransactionById(
      transactionId: TransactionIdString): Future[Option[(Long, LedgerEntry.Transaction)]] =
    ledger
      .lookupTransaction(transactionId)
      .map(_.map { case (offset, t) => (offset + 1) -> t })(DEC)

  override def submitTransaction(
      submitterInfo: ParticipantState.SubmitterInfo,
      transactionMeta: ParticipantState.TransactionMeta,
      transaction: SubmittedTransaction): CompletionStage[ParticipantState.SubmissionResult] =
    FutureConverters.toJava(ledger.publishTransaction(submitterInfo, transactionMeta, transaction))

  override def transactionTrees(
      begin: LedgerOffset,
      endAt: Option[LedgerOffset],
      filter: domain.TransactionFilter): Source[domain.TransactionTree, NotUsed] =
    acceptedTransactions(begin, endAt)
      .mapConcat {
        case (offset, transaction) =>
          TransactionConversion.ledgerEntryToDomainTree(offset, transaction, filter).toList
      }

  override def transactions(
      begin: domain.LedgerOffset,
      endAt: Option[domain.LedgerOffset],
      filter: domain.TransactionFilter): Source[domain.Transaction, NotUsed] =
    acceptedTransactions(begin, endAt)
      .mapConcat {
        case (offset, transaction) =>
          TransactionConversion.ledgerEntryToDomainFlat(offset, transaction, filter).toList
      }

  private class OffsetConverter {
    lazy val currentEndF = currentLedgerEnd()

    def toAbsolute(offset: LedgerOffset) = offset match {
      case LedgerOffset.LedgerBegin =>
        Source.single(LedgerOffset.Absolute(Ref.LedgerString.assertFromString("0")))
      case LedgerOffset.LedgerEnd => Source.fromFuture(currentEndF)
      case off @ LedgerOffset.Absolute(_) => Source.single(off)
    }
  }

  private def acceptedTransactions(begin: domain.LedgerOffset, endAt: Option[domain.LedgerOffset])
    : Source[(LedgerOffset.Absolute, LedgerEntry.Transaction), NotUsed] = {
    val converter = new OffsetConverter()

    converter.toAbsolute(begin).flatMapConcat {
      case LedgerOffset.Absolute(absBegin) =>
        endAt
          .map(converter.toAbsolute(_).map(Some(_)))
          .getOrElse(Source.single(None))
          .flatMapConcat { endOpt =>
            lazy val stream = ledger.ledgerEntries(Some(absBegin.toLong))

            val finalStream = endOpt match {
              case None => stream

              case Some(LedgerOffset.Absolute(`absBegin`)) =>
                Source.empty

              case Some(LedgerOffset.Absolute(end)) if absBegin.toLong > end.toLong =>
                Source.failed(
                  ErrorFactories.invalidArgument(s"End offset $end is before Begin offset $begin."))

              case Some(LedgerOffset.Absolute(end)) =>
                stream
                  .takeWhile(
                    {
                      case (offset, _) =>
                        //note that we can have gaps in the increasing offsets!
                        (offset + 1) < end.toLong //api offsets are +1 compared to backend offsets
                    },
                    inclusive = true // we need this to be inclusive otherwise the stream will be hanging until a new element from upstream arrives
                  )
                  .filter(_._1 < end.toLong)
            }
            // we MUST do the offset comparison BEFORE collecting only the accepted transactions,
            // because currentLedgerEnd refers to the offset of the mixed set of LedgerEntries (e.g. completions, transactions, ...).
            // If we don't do this, the response stream will linger until a transaction is committed AFTER the end offset.
            // The immediate effect is that integration tests will not complete within the timeout.
            finalStream.collect {
              case (offset, t: LedgerEntry.Transaction) =>
                (LedgerOffset.Absolute(LedgerString.assertFromString((offset + 1).toString)), t)
            }
          }
    }
  }

  override def currentLedgerEnd(): Future[LedgerOffset.Absolute] =
    Future.successful(LedgerOffset.Absolute(LedgerString.fromLong(ledger.ledgerEnd)))

  override def getTransactionById(
      transactionId: TransactionId,
      requestingParties: Set[Ref.Party]): Future[Option[domain.Transaction]] = {
    val filter =
      domain.TransactionFilter(requestingParties.map(p => p -> domain.Filters.noFilter).toMap)
    getTransactionById(transactionId.unwrap)
      .map(_.flatMap {
        case (offset, transaction) =>
          TransactionConversion.ledgerEntryToDomainFlat(
            LedgerOffset.Absolute(LedgerString.assertFromString(offset.toString)),
            transaction,
            filter)
      })(DEC)
  }

  override def getTransactionTreeById(
      transactionId: TransactionId,
      requestingParties: Set[Ref.Party]): Future[Option[domain.TransactionTree]] = {
    val filter =
      domain.TransactionFilter(requestingParties.map(p => p -> domain.Filters.noFilter).toMap)
    getTransactionById(transactionId.unwrap)
      .map(_.flatMap {
        case (offset, transaction) =>
          TransactionConversion.ledgerEntryToDomainTree(
            LedgerOffset.Absolute(LedgerString.assertFromString(offset.toString)),
            transaction,
            filter)
      })(DEC)
  }

  override def getCompletions(
      begin: LedgerOffset,
      applicationId: ApplicationId,
      parties: Set[Ref.Party]
  ): Source[CompletionEvent, NotUsed] = {
    val converter = new OffsetConverter()
    converter.toAbsolute(begin).flatMapConcat {
      case LedgerOffset.Absolute(absBegin) =>
        ledger.ledgerEntries(Some(absBegin.toLong)).collect {
          case (offset, t: LedgerEntry.Transaction)
              if (t.applicationId == applicationId.unwrap && parties.contains(t.submittingParty)) =>
            CommandAccepted(
              domain.LedgerOffset.Absolute(Ref.LedgerString.assertFromString(offset.toString)),
              t.recordedAt,
              domain.CommandId(t.commandId),
              domain.TransactionId(t.transactionId)
            )

          case (offset, c: LedgerEntry.Checkpoint) =>
            Checkpoint(
              domain.LedgerOffset.Absolute(Ref.LedgerString.assertFromString(offset.toString)),
              c.recordedAt)
          case (offset, r: LedgerEntry.Rejection) =>
            CommandRejected(
              domain.LedgerOffset.Absolute(Ref.LedgerString.assertFromString(offset.toString)),
              r.recordedAt,
              domain.CommandId(r.commandId),
              r.rejectionReason)
        }
    }
  }

  // IndexPackagesService
  override def listLfPackages(): Future[Map[PackageId, PackageDetails]] =
    packageStore.listLfPackages()

  override def getLfArchive(packageId: PackageId): Future[Option[Archive]] =
    packageStore.getLfArchive(packageId)

  override def getLfPackage(packageId: PackageId): Future[Option[Ast.Package]] =
    packageStore.getLfPackage(packageId)

  // PackageWriteService
  override def uploadDar(
      sourceDescription: String,
      payload: Array[Byte]): CompletionStage[UploadDarResult] =
    packageStore.uploadDar(ledger.getCurrentTime(), sourceDescription, payload)

  // ContractStore
  override def lookupActiveContract(
      submitter: Ref.Party,
      contractId: AbsoluteContractId
  ): Future[Option[ContractInst[Value.VersionedValue[AbsoluteContractId]]]] =
    contractStore.lookupActiveContract(submitter, contractId)

  override def lookupContractKey(
      submitter: Party,
      key: GlobalKey): Future[Option[AbsoluteContractId]] =
    contractStore.lookupContractKey(submitter, key)

  // WriteService (write part of party management)
  override def allocateParty(
      hint: Option[String],
      displayName: Option[String]): CompletionStage[PartyAllocationResult] = {
    // TODO: Implement party management
    CompletableFuture.completedFuture(PartyAllocationResult.NotSupported)
  }

  // PartyManagementService
  override def getParticipantId(): Future[ParticipantId] =
    // In the case of the sandbox, there is only one participant node
    // TODO: Make the participant ID configurable
    Future.successful(ParticipantId(ledger.ledgerId.unwrap))

  override def listParties(): Future[List[PartyDetails]] =
    // TODO: Implement party management
    Future.failed(new RuntimeException("Not implemented"))
}
