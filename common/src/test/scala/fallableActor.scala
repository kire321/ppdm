package ppdm

import akka.actor._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import ExecutionContext.Implicits.global

import ppdm.Constants._

case class DoNotDelay(msg:Any)
case class SetImmune()

case class FallableNode(latencyDistribution:(() => Int), deathProb:Double, expectedNMsgs:Int) extends Node {
  var doomed = random.nextFloat() < deathProb
  val timeOfDeath = random.nextInt(expectedNMsgs)
  var nMsgs = 0
  override def receive = {

    case SetImmune() =>
      doomed = false
      println("Immunity set")
      sender ! Finished()

    case DoNotDelay(anything) =>
      //println("DND " + anything.toString + " " + self.toString + " " + sender.toString)
      super.receive(anything)

    case anything:SafeMsg =>
      //println("Safe msg")
      super.receive(anything)

    case anything:VulnerableMsg =>
      nMsgs += 1
      if (doomed && nMsgs > timeOfDeath) {
        println("Dying")
        context.stop(self)
      } else {
        //println("Delaying " + anything.toString + " " + self.toString + " " + sender.toString)
        val senderCopy = sender
        context.system.scheduler.scheduleOnce(latencyDistribution() milliseconds) {
          self tell(DoNotDelay(anything), senderCopy)
        }
      }

    case anything =>
      println("Match failed " + anything.toString)
  }
}
