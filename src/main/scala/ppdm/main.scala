package ppdm;

import akka.actor._
import akka.util.Timeout

import scala.concurrent.duration._
import scala.util.Random
import collection._

object Constants {
  implicit val timeout = Timeout(5 second)
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
case class Debug()

object Node {
  def apply = {
    val secret = random.nextInt(modulus / groupSize)
    val jobs = new mutable.HashMap[Int, TempDataForSumming]
    new Node(secret, Nil, jobs, false)
  }
  def spawn(name:String, system:ActorSystem) = {system.actorOf(Props(Node.apply), name = name)}
}

case class TempDataForSumming(var sum:Int, var nKeys:Int, var asker:Option[ActorRef])

case class Node (
             val secret:Int,
             var group:List[ActorRef],
             var jobs:mutable.Map[Int, TempDataForSumming],
             var debug:Boolean
             ) extends Actor {
  def jobsString = {
    if (jobs isEmpty)
      "Jobs is empty"
    else
      jobs map {x => "\t" + x._1.toString + "   " + x._2.toString + "\n"} reduce {_ + _}
  }
  def receive = {
    case Debug =>
      println("Debug enabled")
      debug = true
      sender ! Finished
    case GetSecret =>
      if(debug)
        println("GetSecret")
      sender ! secret
    case SetGroup(newGroup) =>
      if(debug)
        println("SetGroup")
      group = newGroup.filter(_ != self)
      sender ! Finished
    case SecureSum(job) =>
      //collection.mutable.HashMap withDefaultValue doesn't work if the default is not primitive
      if (!(jobs contains job))
        jobs(job) = TempDataForSumming(0, 0, None)
      if(debug)
        println("SecureSumBefore\n" + jobsString)
      jobs(job).sum += secret
      group foreach {peer:ActorRef =>
        val key = random.nextInt(modulus)
        peer ! Key(key, job)
        jobs(job).sum -= key
      }
      jobs(job).asker = Some(sender)
      //maybe all the keys came in before we received the SecureSum message
      if (jobs(job).nKeys == group.length) {
        sender ! jobs(job).sum
        if (debug)
          println("SecureSum Finished\n" + jobsString)
      }
      if(debug)
        println("SecureSumAfter\n" + jobsString)
    case Key(key, job) =>
      //collection.mutable.HashMap withDefaultValue doesn't work if the default is not primitive
      if (!(jobs contains job))
        jobs(job) = TempDataForSumming(0, 0, None)
      if (debug)
        println("KeyBefore\n" + jobsString)
      jobs(job).sum += key
      jobs(job).nKeys += 1
      jobs(job).asker match { //have we already gotten a SecureSum message?
        case Some(ref) =>
          if (jobs(job).nKeys == group.length) {//have we collected all the keys?
            ref ! jobs(job).sum
            if (debug)
              println("Key Finished\n" + jobsString)
          }
        case None => Unit
      }
      if (debug)
        println("Key After\n" + jobsString)
    case _ =>
      sender ! Status.Failure(new Exception("Unknown message"))
  }
}