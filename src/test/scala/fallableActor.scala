package ppdm

import akka.actor._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import ExecutionContext.Implicits.global

import ppdm.Constants._

case class DoNotDelay(msg:Any)
case class SetImmune()

case class FallableNode(latencyDistribution:(() => Int), deathProb:Double) extends Node {
  var immune:Boolean = false
  override def receive = {
    case SetImmune() =>
      immune = true
      println("Immunity set")
      sender ! Finished()
    case DoNotDelay(anything) =>
      //println("DND " + anything.toString + " " + self.toString + " " + sender.toString)
      super.receive(anything)
    case anything:SafeMsg =>
      //println("Safe msg")
      super.receive(anything)
    case anything:VulnerableMsg =>
      if (random.nextFloat() < deathProb && !immune) {
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
