package ppdm;

import akka.actor._
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util._
import collection._
import math.abs

object Constants {
  implicit val timeout = Timeout(1 second)
  val random = new Random()
  val modulus = 100
  val groupSize = 10
  val gsTolerance = .5
  type ActorSet = immutable.Set[ActorRef]
  type ActorMap = immutable.Map[ActorSet, List[Int]]
  def shortGroup(group:ActorSet) = (group map (_.path.name)).fold("")(_ + _)
  type Factory = (String, ActorSystem) => ActorRef
}
import Constants._
import NewAskPattern.ask

sealed abstract class VulnerableMsg()
sealed abstract class SafeMsg() //for testing only; we don't want to consider the failure of these messages

case class Finished() extends VulnerableMsg
case class SetGroup(group:mutable.HashSet[ActorRef]) extends SafeMsg
case class GetSecret() extends SafeMsg
case class SecureSum(job:Int) extends VulnerableMsg
case class Key(key:Int, job:Int) extends VulnerableMsg
case class Debug() extends SafeMsg
case class GetDegree() extends SafeMsg
case class AddNeighbor(neighbor:ActorRef) extends SafeMsg
case class StartYourOwnGroup(stableRef:ActorRef) extends VulnerableMsg
case class JoinMe(group:mutable.HashSet[ActorRef], stableRef:ActorRef) extends VulnerableMsg
case class GetGroup() extends VulnerableMsg
case class Gossip(group:mutable.HashSet[ActorRef], debug:Boolean) extends VulnerableMsg
case class GroupingFinishedMsg() extends VulnerableMsg
case class Start() extends SafeMsg
case class Invite() extends VulnerableMsg
case class GetNeighbors() extends SafeMsg
case class TreeSum() extends VulnerableMsg

object Node {
  def spawn(name:String, system:ActorSystem) = {
    system.actorOf(Props(new Node), name = name)
  }
}

case class TempDataForSumming(var sum:Int, var nKeys:Int, var asker:Option[ActorRef])

class Node extends Actor {
  val secret = random.nextInt(modulus / (groupSize * 2))
  var group = mutable.HashSet[ActorRef]()
  var jobs = new mutable.HashMap[Int, TempDataForSumming]
  var debug = false
  var neighbors = mutable.HashSet[ActorRef]()
  var parent:Option[ActorRef] = None
  var unfinishedChildren = 0
  var finishedHere = false
  var outsiders = mutable.HashSet[ActorRef]()
  var children = mutable.HashSet[ActorRef]()

  def jobsString = {
    if (jobs isEmpty)
      "Jobs is empty"
    else
      jobs map {x => "\t" + x._1.toString + "   " + x._2.toString + "\n"} reduce {_ + _}
  }

  def maybeJoinSmallestGroup = {

  }

  def groupingFinishedMethod = {
    if (unfinishedChildren == 0)
      parent match {
        case Some(pRef) =>
          if (neighbors.size == 0)
            println("I'm lonely in groupingFinishedMethod")
          pRef ! HeartBeat()
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
              reassuredConcernedParent = pRef ! HeartBeat()
              gossipingDone <- SafeFuture.traverse(group)(_ ? Gossip(group + self, debug))
            } yield pRef ! GroupingFinishedMsg()
            groupsFixed onFailure {case e:Throwable => throw e}
          } else {
            if (debug)
              println("Reporting done")
            pRef ! GroupingFinishedMsg()
          }
        case None => println("GroupingFinished: parent is None")
      }
    else
      finishedHere = true
  }


  def inviteLater = context.system.scheduler.scheduleOnce(100 milliseconds, self, Invite())

  def merge(left:ActorMap, right:ActorMap) = {
    immutable.HashMap(((left.keySet ++ right.keySet) map {key =>
      (key, left.getOrElse(key, Nil) ::: right.getOrElse(key, Nil))
    }).toList:_*)
  }

  def receive: PartialFunction[Any, Unit] = {
    case HeartBeat() => ()
    case TreeSum() =>
      val senderCopy = sender
      if (debug)
        println("Tree sum")
      parent match {
        case Some(pRef) => {
          val job = Random.nextInt()
          val immutableGroup = immutable.HashSet(group.toSeq:_*) + self
          val groupSum = Future.fold(immutableGroup map {node => (node ? SecureSum(job)).mapTo[Int]})(0)(_ + _)
          val childrenSums = children map {node => (node ? TreeSum()).mapTo[ActorMap]}
          val treeSum = for {
            childrenTable <- Future.fold(childrenSums)(immutable.HashMap[ActorSet, List[Int]]():ActorMap)(merge(_,_))
            groupSum <- groupSum
            groupTable = immutable.HashMap((immutableGroup, List(groupSum))):ActorMap
          } yield senderCopy ! merge(childrenTable, groupTable)
          treeSum onFailure {case e: Throwable => throw e}
        }
        case None =>
          sender ! Status.Failure(new Exception("Parent is none. Did you TreeSum before forming groups?"))
      }

    case GetNeighbors() =>
      if (debug)
        println("Get neighbors")
      if (neighbors.size == 0)
        println("I'm lonely in getNeighbors")
      sender ! neighbors

    case Invite() =>
      if (debug)
        println("inviteFrom was called")
      parent match {
        case Some(pRef) =>
          pRef ! HeartBeat()
      }
      if (group.size < groupSize) {
        if (debug)
          println("Inviting")
        outsiders headOption match {
          case Some(outsider) => outsider ? JoinMe(group + self, self) onComplete {
            case Success(_) =>
              group add outsider
              outsiders remove outsider
              unfinishedChildren += 1
              children add outsider
              inviteLater
            case Failure(_) =>
              outsiders remove outsider
              self ! Invite()
          }
          case None => groupingFinishedMethod
        }
      } else {
        if (debug)
          println("Rejecting")
        Future.traverse(outsiders)({outsider =>
          outsider ? StartYourOwnGroup(self) andThen {
            case Success(_) =>
              unfinishedChildren +=1
              children add outsider
            case Failure(_) => Unit
          }
        }) onComplete {case _ => groupingFinishedMethod}
      }

    case Start() =>
      if (debug)
        println("Start")
      self ! StartYourOwnGroup(sender)

    case Finished() =>
      if (false)
        println("Finished")
    case GetGroup() =>
      if (debug)
        println("GetGroup")
      sender ! group + self
    case GroupingFinishedMsg() =>
      if (debug)
        println("GroupingIsDone")
      parent match {
        case Some(pRef) =>
          pRef ! HeartBeat()
      }
      unfinishedChildren -= 1
      if (finishedHere)
        groupingFinishedMethod

    case StartYourOwnGroup(stableRef) =>
      if (neighbors.size == 0)
        println("I'm lonely in StartYourOwnGroup")
      if (debug)
          println("StartYourOwnGroup")
        parent match {
          case Some(_) => sender ! Status.Failure(new Exception)
          case None => {
            parent = Some(stableRef)
            sender ! Finished()
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
        case Some(_) => sender ! Status.Failure(new Exception)
        case None => {
          parent = Some(stableRef)
          group = newGroup
          sender ! Finished()
          for (member <- group)
            member ! Gossip(group + self, debug)
          outsiders = neighbors - stableRef
          inviteLater
        }
      }

    case Gossip(similarGroup, otherDebug) =>
      if (false)
        println("Gossip " + group.size.toString)// + "\n" + similarGroup.toString + "\n" + (group + self).toString)
      if (!(similarGroup subsetOf group + self)) {
        group ++= similarGroup - self
        if (debug)
          println("Gossiping " + group.size.toString)//self.toString + "\n" + group.size.toString + "\n" + similarGroup.toString + "\n" + (group + self).toString)
        for (member <- group)
          member ! Gossip(group + self, debug)
      } else {
        if (false)
          println("Not gossiping")
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

    case Debug() =>
      println("Debug enabled")
      debug = true
      sender ! Finished()

    case GetSecret() =>
      if (debug)
        println("GetSecret " + secret.toString)
      sender ! secret

    case SetGroup(newGroup) =>
      if(debug)
        println("SetGroup")
      group = newGroup.filter(_ != self)
      sender ! Finished()

    case SecureSum(job) =>
      if (debug)
        println("SecureSum")
      //collection.mutable.HashMap withDefaultValue doesn't work if the default is not primitive
      if (!(jobs contains job))
        jobs(job) = TempDataForSumming(0, 0, None)
      if(false)
        println("SecureSumBefore\n" + jobsString)
      jobs(job).sum += secret
      group foreach {peer:ActorRef =>
        val key = random.nextInt(modulus)
        peer ! Key(key, job)
        jobs(job).sum -= key
      }
      jobs(job).asker = Some(sender)
      //maybe all the keys came in before we received the SecureSum message
      if (jobs(job).nKeys == group.size) {
        sender ! jobs(job).sum
        if (debug)
          println("SecureSum Finished")
      }

    case Key(key, job) =>
      //collection.mutable.HashMap withDefaultValue doesn't work if the default is not primitive
      if (!(jobs contains job))
        jobs(job) = TempDataForSumming(0, 0, None)
      if (false)
        println("KeyBefore\n" + jobsString)
      jobs(job).sum += key
      jobs(job).nKeys += 1
      jobs(job).asker match { //have we already gotten a SecureSum message?
        case Some(ref) =>
          if (jobs(job).nKeys == group.size) {//have we collected all the keys?
            ref ! jobs(job).sum
            if (debug)
              println("Key Finished")
          }
        case None => Unit
      }
      if (false)
        println("Key After\n" + jobsString)

    case anything =>
      sender ! Status.Failure(new Exception("Unknown message: " + anything.toString))
  }
}