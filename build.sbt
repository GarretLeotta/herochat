organization := "Garret"

name := "Herochat"

version := "DEV-0.1.0"

resolvers ++= Seq(
  "sonatype-public" at "https://oss.sonatype.org/content/groups/public",
)


libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.5.11",
  "com.typesafe.akka" %% "akka-stream" % "2.5.11",
  "com.typesafe.akka" %% "akka-http" % "10.1.0",

  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",

  "org.scodec" %% "scodec-core" % "1.10.3",
  "org.scodec" %% "scodec-bits" % "1.1.5",
  "org.scodec" %% "scodec-stream" % "1.1.0",
  "co.fs2" %% "fs2-core" % "0.10.5",
  "org.scalafx" %% "scalafx" % "8.0.181-R13",
  "org.scalacheck" %% "scalacheck" % "1.13.5",

  "com.typesafe.akka" %% "akka-slf4j" % "2.5.19",
  "ch.qos.logback" % "logback-classic" % "1.2.3",

  "org.json4s" %% "json4s-native" % "3.6.4"
)

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
)


/* OpenJDK 12 isn't out yet
lazy val osName = System.getProperty("os.name") match {
  case n if n.startsWith("Linux")   => "linux"
  case n if n.startsWith("Mac")     => "mac"
  case n if n.startsWith("Windows") => "win"
  case _ => throw new Exception("Unknown platform!")
}

lazy val javaFXModules = Seq("base", "controls", "fxml", "graphics", "media", "swing", "web")
libraryDependencies ++= javaFXModules.map( m =>
  "org.openjfx" % s"javafx-$m" % "12" classifier osName
)
*/


mainClass in (Compile, run) := Some("herochat.MVCAkkaTest")
//mainClass in (Compile, run) := Some("herochat.Main")

mainClass in (Compile, packageBin) := Some("herochat.Main")
mainClass in assembly := Some("herochat.Main")




enablePlugins(JavaAppPackaging)
