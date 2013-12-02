package ppdm

import akka.actor._
import scala.collection.{TraversableOnce, mutable}

import Constants._
import NewAskPattern.ask

case class GetSecret() extends SafeMsg
case class StartSecureSum(job:Int, group:TraversableOnce[ActorRef]) extends VulnerableMsg
case class Key(key:Int, job:Int) extends VulnerableMsg
case class Debug() extends SafeMsg

case class TempDataForSumming(var sum:Int, var keysRemaining:Int, var asker:Option[ActorRef])

trait SecureSum extends Actor {

  val secret = random.nextInt(modulus / (groupSize * 2))
  var jobs = new mutable.HashMap[Int, TempDataForSumming]
  var debug = false

  def receive = {

    case GetSecret() =>
      if (debug)
        println("GetSecret " + secret.toString)
      sender ! secret

    case Debug() =>
      println("Debug enabled")
      debug = true
      sender ! Finished()

    case StartSecureSum(job, aliveGroup) =>
    if (debug)
      println("SecureSum")
    //collection.mutable.HashMap withDefaultValue doesn't work if the default is not primitive
    if (!(jobs contains job))
      jobs(job) = TempDataForSumming(0, 0, None)
    jobs(job).sum += secret
    aliveGroup foreach {peer:ActorRef =>
      val key = random.nextInt(modulus)
      peer ! Key(key, job)
      jobs(job).sum -= key
    }
    jobs(job).asker = Some(sender)
    jobs(job).keysRemaining += aliveGroup.size
    //maybe all the keys came in before we received the SecureSum message
    if (jobs(job).keysRemaining == 0) {
      sender ! jobs(job).sum
      if (debug)
        println("SecureSum Finished")
    }

    case Key(key, job) =>
    //collection.mutable.HashMap withDefaultValue doesn't work if the default is not primitive
    if (!(jobs contains job))
      jobs(job) = TempDataForSumming(0, 0, None)
    jobs(job).sum += key
    jobs(job).keysRemaining -= 1
    jobs(job).asker match { //have we already gotten a SecureSum message?
      case Some(ref) =>
        if (jobs(job).keysRemaining == 0) {//have we collected all the keys?
          ref ! jobs(job).sum
          if (debug)
            println("Key Finished")
        } else {
          ref ! HeartBeat()
        }
      case None => Unit
    }

    case anything =>
      sender ! Status.Failure(new Exception("Unknown message: " + anything.toString))
  }
}
