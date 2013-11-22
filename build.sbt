import AssemblyKeys._

assemblySettings

name := "privacy_preserving_data_mining"

version := "1.0"

scalaVersion := "2.10.2"

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.2.1"

libraryDependencies += "com.typesafe.akka" %% "akka-remote" % "2.2.1"

libraryDependencies += "org.scalatest" %% "scalatest" % "2.0.M8" % "test"

testOptions in Test += Tests.Argument("-oDF")

lazy val common = project

lazy val daemon = project.dependsOn(common).settings(assemblySettings: _*)

lazy val client = project.dependsOn(common).settings(assemblySettings: _*)
