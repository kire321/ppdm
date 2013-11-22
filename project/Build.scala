import sbt._
import Keys._

object Build extends Build {
  lazy val common = project
  lazy val daemon = project dependsOn(common)
  lazy val client = project dependsOn(common)
}
