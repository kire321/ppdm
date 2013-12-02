package ppdm

import akka.actor._

import scala.concurrent.duration._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.math.abs
import scala.Some
import scala.util.{Failure, Success}

import Constants._
import NewAskPattern.ask

case class StartYourOwnGroup(stableRef:ActorRef) extends VulnerableMsg
case class JoinMe(group:mutable.HashSet[ActorRef], stableRef:ActorRef) extends VulnerableMsg
case class GroupingStarting() extends VulnerableMsg
case class GroupingFinishedMsg() extends VulnerableMsg
case class Start() extends SafeMsg
case class Invite() extends VulnerableMsg
case class JoinMeFinished() extends VulnerableMsg
case class JoinMeFailed() extends VulnerableMsg
case class StartYourOwnGroupFinished() extends VulnerableMsg
case class StartYourOwnGroupFailed() extends VulnerableMsg


trait Grouping extends Gossip {

  var parent:Option[ActorRef] = None
  val askerPromise = Promise[Any]()
  var unfinishedChildren = mutable.MutableList(askerPromise.future)
  var sizeOfUnfinishedChildrenWhenGroupingFinishedWasLastScheduled = unfinishedChildren.size
  var outsiders = mutable.HashSet[ActorRef]()
  var children = mutable.HashSet[ActorRef]()

  def groupingFinishedMethod = {
    if (debug)
      println("grouping finished method")
    asker match {
      case Some(aRef) =>
        if (neighbors.size == 0)
          println("I'm lonely in groupingFinishedMethod")
        reassureParent
        if (group.size + 1 < groupSize * gsTolerance) {
          val groupsFixed = for {
            neighborGroups <- SafeFuture.traverse(neighbors)(_ ? GetGroup()).mapTo[mutable.Set[mutable.Set[ActorRef]]]
            otherGroup = neighborGroups reduce {(left, right)  =>
              if (abs(left.size - groupSize) < abs(right.size - groupSize))
                left
              else
                right
            }
            merged = group ++= otherGroup
            reassuredConcernedParent = reassureParent
            gossipingDone <- SafeFuture.traverse(group)(_ ? GossipMsg(group + self))
          } yield aRef ! GroupingFinishedMsg()
          groupsFixed onFailure {case e:Throwable => throw e}
        } else {
          if (debug)
            println("Reporting done")
          aRef ! GroupingFinishedMsg()
        }
      case None =>
        throw new Exception("GroupingFinished: asker is none")
    }
  }

  def scheduleGroupingFinished:Unit = {
    sizeOfUnfinishedChildrenWhenGroupingFinishedWasLastScheduled = unfinishedChildren.size
    SafeFuture.sequence(unfinishedChildren) onComplete {
      case Success(list) =>
        if (unfinishedChildren.size == sizeOfUnfinishedChildrenWhenGroupingFinishedWasLastScheduled)
          groupingFinishedMethod
        else
          scheduleGroupingFinished
      case Failure(e) =>
        throw e
    }
  }

  def inviteLater = context.system.scheduler.scheduleOnce(1 second, self, Invite())

  override def receive = {

    case StartYourOwnGroupFinished() => ()

    case Invite() =>
      if (debug)
        println("inviteFrom was called")
      reassureParent
      if (group.size < groupSize) {
        if (debug)
          println("Inviting")
        outsiders headOption match {
          case Some(outsider) => outsider ? JoinMe(group + self, self) onComplete {
            case Success(JoinMeFinished()) =>
              group add outsider
              outsiders remove outsider
              unfinishedChildren += PatientAsk(outsider, GroupingStarting(), asker)
              children add outsider
              inviteLater
            case _ =>
              outsiders remove outsider
              self ! Invite()
          }
          case None => scheduleGroupingFinished
        }
      } else {
        if (debug)
          println(s"Rejecting ${outsiders}")
        SafeFuture.traverse(outsiders)({outsider =>
          outsider ? StartYourOwnGroup(self) andThen {
            case _ =>
              if (debug)
                println(s"heard back from ${outsider.path.name}")
          }  andThen {
            case Success(StartYourOwnGroupFinished()) =>
              unfinishedChildren += PatientAsk(outsider, GroupingStarting(), asker)
              children add outsider
              reassureParent
            case _ =>
              reassureParent
          }
        }) onComplete {case _ => scheduleGroupingFinished}
      }

    case Start() =>
      if (debug)
        println("Start")
      self ! StartYourOwnGroup(sender)
      self.tell(GroupingStarting(), sender)


    case GroupingStarting() =>
      if (debug)
        println("GroupingStarting")
      asker match {
        case Some(original) =>
          println(s"claimed as a child redundantly by ${sender.path.name}, original claim by ${original.path.name}")
        case None =>
          asker = Some(sender)
          askerPromise success Finished()
      }

    case StartYourOwnGroup(stableRef) =>
      if (neighbors.size == 0)
        println("I'm lonely in StartYourOwnGroup")
      if (debug)
        println("StartYourOwnGroup")
      parent match {
        case Some(_) => sender ! StartYourOwnGroupFailed()
        case None => {
          parent = Some(stableRef)
          sender ! StartYourOwnGroupFinished()
          outsiders = mutable.HashSet[ActorRef](neighbors.toSeq: _*)
          self ! Invite()
        }
      }

    case JoinMe(newGroup, stableRef) =>
      if (neighbors.size == 0)
        println("I'm lonely in JoinMe")
      if (debug)
        println("JoinMe")
      parent match {
        case Some(_) => {
          sender ! JoinMeFailed()
          reassureParent
        }
        case None => {
          parent = Some(stableRef)
          group = newGroup
          sender ! JoinMeFinished()
          for (member <- group)
            member ! GossipMsg(group + self)
          outsiders = neighbors - stableRef
          inviteLater
        }
      }

    case anything =>
      super.receive(anything)
  }
}
