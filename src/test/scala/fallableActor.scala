package ppdm

import akka.actor._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import ExecutionContext.Implicits.global

import ppdm.Constants._

case class DoNotDelay(msg:Any)

case class FallableNode(latencyDistribution:(() => Int), deathProb:Float) extends Node {
  override def receive = {
    case DoNotDelay(anything) =>
      //println("DND " + anything.toString + " " + self.toString + " " + sender.toString)
      super.receive(anything)
    case anything =>
      if (random.nextFloat() < deathProb) {
        println("Dying")
        context.stop(self)
      } else {
        //println("Delaying " + anything.toString + " " + self.toString + " " + sender.toString)
        val senderCopy = sender
        context.system.scheduler.scheduleOnce(latencyDistribution() milliseconds) {
          self tell(DoNotDelay(anything), senderCopy)
        }
      }
  }
}
