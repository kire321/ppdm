package ppdm;

import akka.actor._
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Await}
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import scala.util.Random

object Constants {
  implicit val timeout = Timeout(1 second)
  val random = new Random()
}
import Constants._

case class Finished()
case class SetGroup(group:IndexedSeq[ActorRef])
case class GetSecret()
case class SecureSum()

object Node {
  def apply = {new Node(random.nextInt(100), Nil)}
  val system = ActorSystem("ppdm")
  def spawn(name:String) = {system.actorOf(Props(Node.apply), name = name)}
}

class Node (val secret:Int, var group:List[ActorRef]) extends Actor {
  def receive = {
    case GetSecret =>
      sender ! secret
  }
}