package ppdm;

import akka.actor._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Await, Future}
import ExecutionContext.Implicits.global
import collection._

import ppdm.Constants._
import NewAskPattern.ask


class mockNode extends Actor {
  def receive = {

    case Start() =>
      sender ! Finished

    case TreeSum() =>
      sender ! (immutable.HashMap((immutable.HashSet(self), List(0))):ActorMap)
  }
}

object Main extends App {
  val system = ActorSystem("daemon")
  system.actorOf(Props(new mockNode), name = "node")
  //Node.spawn("node", system)
  Thread.currentThread().interrupt()
}
