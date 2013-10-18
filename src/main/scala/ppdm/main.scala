package ppdm;

import akka.actor._
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util._
import collection._

object Constants {
  implicit val timeout = Timeout(10 second)
  val random = new Random()
  val modulus = 100
  val groupSize = 10
}
import Constants._

case class Finished()
case class SetGroup(group:mutable.Set[ActorRef])
case class GetSecret()
case class SecureSum(job:Int)
case class Key(key:Int, job:Int)
case class Debug()
case class GetDegree()
case class AddNeighbor(neighbor:ActorRef)
case class StartYourOwnGroup(stableRef:ActorRef)
case class JoinMe(group:mutable.Set[ActorRef], groupID:Int, stableRef:ActorRef)
case class GetGroup()
case class Gossip(group:mutable.Set[ActorRef], groupID:Int, debug:Boolean)
case class GroupingIsDone()
case class Start()
case class GetGroupID()
case class Invite()

object Node {
  def apply = {
    //In reality, we should be choosing moduli based on secrets and not the other way around
    val secret = random.nextInt(modulus / (groupSize * 2))
    val jobs = new mutable.HashMap[Int, TempDataForSumming]
    new Node(secret, mutable.HashSet[ActorRef](), None, jobs, false, mutable.HashSet[ActorRef](), None, 0, false, mutable.HashSet[ActorRef]())
  }
  def spawn(name:String, system:ActorSystem) = {system.actorOf(Props(Node.apply), name = name)}
}

case class TempDataForSumming(var sum:Int, var nKeys:Int, var asker:Option[ActorRef])

case class Node (
             val secret:Int,
             var group:mutable.Set[ActorRef],
             var groupID:Option[Int],
             var jobs:mutable.Map[Int, TempDataForSumming],
             var debug:Boolean,
             var neighbors:mutable.Set[ActorRef],
             var parent:Option[ActorRef],
             var unfinishedChildren:Int,
             var finishedHere:Boolean,
             var outsiders:mutable.Set[ActorRef]
             ) extends Actor {
  def jobsString = {
    if (jobs isEmpty)
      "Jobs is empty"
    else
      jobs map {x => "\t" + x._1.toString + "   " + x._2.toString + "\n"} reduce {_ + _}
  }

  def groupingFinished = {
    if (unfinishedChildren == 0)
      parent match {
        case Some(pRef) =>
          if (debug)
            println("Reporting done")
          pRef ! GroupingIsDone
        case None => println("GroupingFinished: parent is None")
      }
    else
      finishedHere = true
  }

  def inviteLater = context.system.scheduler.scheduleOnce(100 milliseconds, self, Invite)

  def receive = {
    case Invite =>
      if (debug)
        println("inviteFrom was called")
      if (group.size < groupSize) {
        if (debug)
          println("Inviting")
        groupID match {
          case Some(groupIDInt) =>
            outsiders headOption match {
              case Some(outsider) => outsider ? JoinMe(group + self, groupIDInt, self) onComplete {
                case Success(_) =>
                  group add outsider
                  outsiders remove outsider
                  unfinishedChildren += 1
                  inviteLater
                case Failure(_) =>
                  outsiders remove outsider
                  self ! Invite
              }
              case None => groupingFinished
            }
          case None => println("InviteFrom: no group id")
        }
      } else {
        if (debug)
          println("Rejecting")
        Future.traverse(outsiders)({outsider =>
          outsider ? StartYourOwnGroup(self) andThen {
            case Success(_) =>
              unfinishedChildren +=1
            case Failure(_) => Unit
          }
        }) onComplete {case _ => groupingFinished}
      }
    case GetGroupID =>
      sender ! (groupID, self)
    case Start =>
      if (debug)
        println("Start")
      self ! StartYourOwnGroup(sender)
    case Finished =>
      if (debug)
        println("Finished")
    case GetGroup =>
      if (debug)
        println("GetGroup")
      sender ! group + self
    case GroupingIsDone =>
      if (debug)
        println("GroupingIsDone")
      unfinishedChildren -= 1
      if (finishedHere)
        groupingFinished
    case StartYourOwnGroup(stableRef) =>
        if (debug)
          println("StartYourOwnGroup")
        parent match {
          case Some(_) => sender ! Status.Failure(new Exception)
          case None => {
            parent = Some(stableRef)
            groupID = Some(random.nextInt())
            sender ! Finished
            outsiders = neighbors
            self ! Invite
          }

        }
    case JoinMe(newGroup, newGroupID, stableRef) =>
      if (debug)
        println("JoinMe")
      parent match {
        case Some(_) => sender ! Status.Failure(new Exception)
        case None => {
          parent = Some(stableRef)
          group = newGroup
          groupID = Some(newGroupID)
          sender ! Finished
          for (member <- group)
            member ! Gossip(group + self, newGroupID, debug)
          outsiders = neighbors - stableRef
          inviteLater
        }
      }
    case Gossip(similarGroup, otherGroupID, otherDebug) =>
      if (false)
        println("Gossip " + group.size.toString)// + "\n" + similarGroup.toString + "\n" + (group + self).toString)
      groupID match {
        case Some(groupIDInt) =>
          if (groupIDInt == otherGroupID) {
            if (!(similarGroup subsetOf group + self)) {
              group ++= similarGroup - self
              if (debug)
                println("Gossiping " + group.size.toString)//self.toString + "\n" + group.size.toString + "\n" + similarGroup.toString + "\n" + (group + self).toString)
              for (member <- group)
                member ! Gossip(group + self, groupIDInt, debug)
            } else {
              if (false)
                println("Not gossiping")
            }
          } else {
             if (debug)
              println("Gossip: wrong group ID")
          }
        case None =>
          println("Gossip: no group ID")
      }

    case GetDegree =>
      if (debug)
        println("GetDegree")
      sender ! neighbors.size
    case AddNeighbor(neighbor) =>
      neighbors add neighbor
      if (debug)
        println("AddNeighbor, degree is " + neighbors.size.toString)
      sender ! Finished
    case Debug =>
      println("Debug enabled")
      debug = true
      sender ! Finished
    case GetSecret =>
      if (debug)
        println("GetSecret " + secret.toString)
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
      if (jobs(job).nKeys == group.size) {
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
          if (jobs(job).nKeys == group.size) {//have we collected all the keys?
            ref ! jobs(job).sum
            if (debug)
              println("Key Finished\n" + jobsString)
          }
        case None => Unit
      }
      if (debug)
        println("Key After\n" + jobsString)
    case anything =>
      sender ! Status.Failure(new Exception("Unknown message: " + anything.toString))
  }
}