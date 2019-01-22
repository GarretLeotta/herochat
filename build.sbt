organization := "Garret"

name := "Actor Test"

version := "1.0"

resolvers ++= Seq(
  "sonatype-public" at "https://oss.sonatype.org/content/groups/public",
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.5.11",
  "com.typesafe.akka" %% "akka-stream" % "2.5.11",
  "com.typesafe.akka" %% "akka-http" % "10.1.0",
  "org.scodec" %% "scodec-core" % "1.10.3",
  "org.scodec" %% "scodec-bits" % "1.1.5",
  "org.scodec" %% "scodec-stream" % "1.1.0",
  "co.fs2" %% "fs2-core" % "0.10.5",
  "org.scalafx" %% "scalafx" % "8.0.181-R13",
  "org.scalacheck" %% "scalacheck" % "1.13.5",

  "com.typesafe.akka" %% "akka-slf4j" % "2.5.19",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
)

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
)

mainClass in (Compile, run) := Some("herochat.MVCAkkaTest")
//mainClass in (Compile, run) := Some("herochat.ATest")
