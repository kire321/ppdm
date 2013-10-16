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
  val modulus = 100
  val groupSize = 10
}
import Constants._

case class Finished()
case class SetGroup(group:List[ActorRef])
case class GetSecret()
case class SecureSum(job:Int)
case class Key(key:Int, job:Int)

object Node {
  def apply = {
    val secret = random.nextInt(modulus / groupSize)
    new Node(secret, Nil, secret, 0, None)}
  def spawn(name:String, system:ActorSystem) = {system.actorOf(Props(Node.apply), name = name)}
}

class Node (
             val secret:Int,
             var group:List[ActorRef],
             var sum:Int,
             var nKeys:Int,
             var asker:Option[ActorRef]
             ) extends Actor {
  def receive = {
    case GetSecret =>
      sender ! secret
    case SetGroup(newGroup) =>
      group = newGroup.filter(_ != self)
      sender ! Finished
    case SecureSum(job) =>
      group foreach {peer:ActorRef =>
        val key = 1//random.nextInt(modulus)
        peer ! Key(key, 0)
        sum -= key
      }
      //Case 1: All the keys came in before we receive the SecureSum message
      if (nKeys == group.length)
        sender ! sum
      else //Case 2: SecureSum came first
        asker = Some(sender)
    case Key(key, job) =>
      sum += key
      nKeys += 1
      asker match { //have we already gotten a SecureSum message?
        case Some(ref) =>
          if (nKeys == group.length) {//have we collected all the keys?
            ref ! sum
          }
        case None => Unit
      }
    case _ =>
      sender ! Status.Failure(new Exception("Unknown message"))
  }
}