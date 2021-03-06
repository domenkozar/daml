// Copyright (c) 2019 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

object Versions {
  private val daSdkVersionKey = "da.sdk.version"

  val daSdkVersion: String = sys.props.get(daSdkVersionKey).getOrElse(sdkVersionFromFile())
  println(s"$daSdkVersionKey = $daSdkVersion")

  lazy val detectedOs: String = sys.props("os.name") match {
    case "Mac OS X" => "osx"
    case _ => "linux"
  }

  private def sdkVersionFromFile(): String =
    "10" + sbt.IO.read(new sbt.File("./SDK_VERSION").getAbsoluteFile).trim
}
