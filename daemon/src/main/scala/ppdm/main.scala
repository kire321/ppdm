package ppdm;

import akka.actor._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Await, Future}
import ExecutionContext.Implicits.global

import ppdm.Constants._
import NewAskPattern.ask

object Main extends App {
  val system = ActorSystem("daemon")
  Node.spawn("node", system)
  Thread.currentThread().interrupt()
}
