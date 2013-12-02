package ppdm

import akka.actor._
import scala.collection.{TraversableOnce, mutable}

import Constants._
import NewAskPattern.ask

case class Finished() extends VulnerableMsg
case class GetDegree() extends SafeMsg
case class AddNeighbor(neighbor:ActorRef) extends SafeMsg
case class GetGroup() extends VulnerableMsg
case class GossipMsg(group:mutable.HashSet[ActorRef]) extends VulnerableMsg
case class GetNeighbors() extends SafeMsg

trait Gossip extends SecureSum {

  var group = mutable.HashSet[ActorRef]()
  var neighbors = mutable.HashSet[ActorRef]()
  var asker:Option[ActorRef] = None

  def reassureParent = {
    asker match {
      case Some(aRef) =>
        aRef ! HeartBeat()
      case None => ()
    }
  }

  override def receive = {

    case Finished() =>
      if (false)
        println("Finished")

    case GetGroup() =>
      if (debug)
        println("GetGroup")
      sender ! group + self

    case GetNeighbors() =>
      if (debug)
        println("Get neighbors")
      if (neighbors.size == 0)
        println("I'm lonely in getNeighbors")
      sender ! neighbors

    case GossipMsg(similarGroup) =>
      if (!(similarGroup subsetOf group + self)) {
        group ++= similarGroup - self
        reassureParent
        for (member <- group)
          member ! GossipMsg(group + self)
      }
      sender ! Finished()

    case GetDegree() =>
      if (debug)
        println("GetDegree")
      sender ! neighbors.size

    case AddNeighbor(neighbor) =>
      neighbors add neighbor
      if (debug)
        println("AddNeighbor, degree is " + neighbors.size.toString)
      sender ! Finished()

    case anything =>
      super.receive(anything)
  }
}
