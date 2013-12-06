package ppdm;

import akka.actor._
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util._
import collection._
import java.util.concurrent.TimeoutException

object Constants {
  implicit val timeout = Timeout(2 seconds)
  val random = new Random()
  val modulus = Int.MaxValue
  val groupSize = 10
  val gsTolerance = .5
  type ActorSet = immutable.Set[ActorRef]
  type ActorMap = immutable.Map[ActorSet, List[Int]]
  def shortGroup(group:TraversableOnce[ActorRef]) = (group map (_.path.name)).fold("")(_ + _)
}
import Constants._
import NewAskPattern.ask

abstract class VulnerableMsg()
abstract class SafeMsg() //for testing only; we don't want to consider the failure of these messages

case class TreeSum() extends VulnerableMsg
case class Ping() extends SafeMsg

object Node {

  def spawn(name:String, system:ActorSystem) = {
    system.actorOf(Props(new Node), name = name)
  }

  def secureSumWithRetry(group:TraversableOnce[ActorRef])(implicit context:ActorContext):Future[Int] = {
    secureSumWithRetry(group, context.system)
  }

  def secureSumWithRetry(group:TraversableOnce[ActorRef], system:ActorSystem):Future[Int] = {
    val job = Random.nextInt()
    for {
      stillAlive <- SafeFuture.traverse(group){node => (node ? Ping()) map (_ => node)}
      groupSum = Future.fold(stillAlive map {node => PatientAsk(node, StartSecureSum(job, stillAlive), system).mapTo[Int]})(0)(_ + _)
      fallback <- groupSum recoverWith {
        case e:TimeoutException => secureSumWithRetry(group, system)
      }
    } yield fallback
  }
}

class Node extends Actor with Grouping {

  def merge(left:ActorMap, right:ActorMap) = {
    immutable.HashMap(((left.keySet ++ right.keySet) map {key =>
      (key, left.getOrElse(key, Nil) ::: right.getOrElse(key, Nil))
    }).toList:_*)
  }

  override def receive: PartialFunction[Any, Unit] = {

    case Ping() => sender ! Finished()

    case TreeSum() =>
      val senderCopy = sender
      if (debug)
        println("Tree sum")
      parent match {
        case Some(pRef) => {
          val immutableGroup = immutable.HashSet(group.toSeq:_*) + self
          val groupSum = Node.secureSumWithRetry(immutableGroup)
          val childrenSums = children map {node =>
            PatientAsk(node, TreeSum()).mapTo[ActorMap] andThen {case _ => senderCopy ! HeartBeat()}
          }
          val treeSum = for {
            childrenTable <- SafeFuture.fold(childrenSums)(immutable.HashMap[ActorSet, List[Int]]():ActorMap)(merge(_,_))
            groupSum <- groupSum
            groupTable = immutable.HashMap((immutableGroup, List(groupSum))):ActorMap
          } yield senderCopy ! merge(childrenTable, groupTable)
          treeSum onFailure {case e: Throwable => throw e}
        }
        case None =>
          sender ! Status.Failure(new Exception("Parent is none. Did you TreeSum before forming groups?"))
      }

    case anything =>
      super.receive(anything)

  }
}