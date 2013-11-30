package ppdm;

import akka.actor._
import akka.util.Timeout

import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global

import java.util.concurrent.TimeoutException

object NewAskPattern{
  implicit def ask(ref:ActorRef) = new BetterTimeoutMessageSupportAskableRef(ref)
}

class BetterTimeoutMessageSupportAskableRef(ref: ActorRef) {
  import akka.pattern.AskableActorRef
  val askRef = new AskableActorRef(ref)

  def ask(message: Any)(implicit timeout: Timeout): Future[Any] =
    (askRef ? message) recover{
      case to:TimeoutException =>
        val recip = askRef.actorRef.path
        val dur = timeout.duration
        throw new TimeoutException(s"Timed out sending message $message to recipient $recip using timeout of $dur")
    }

  def ?(message: Any)(implicit timeout: Timeout): Future[Any] =
    ask(message)(timeout)
}

object PatientAsk {
  val random = new java.util.Random()

  def apply(server:ActorRef, msg:Any)(implicit timeout:Timeout, context:ActorContext): Future[Any] = {
    apply(server, msg, context.system)
  }

  def apply(server:ActorRef, msg:Any, parent:Option[ActorRef])(implicit timeout:Timeout, context:ActorContext): Future[Any] = {
    apply(server, msg, context.system, parent)
  }

  def apply(server:ActorRef, msg:Any, system:ActorSystem, parent:Option[ActorRef] = None)(implicit timeout:Timeout): Future[Any] = {
    val promise = Promise[Any]()
    val ex = new TimeoutException(s"Timed out sending message $msg to recipient ${server.path} using timeout of ${timeout.duration}")
    val client = system.actorOf(Props(PatientAskActor(timeout, promise, ex, parent)), name = "PatientAsk" + random.nextInt().toString)
    server.tell(msg, client)
    promise.future
  }
}

case class PatientAskActor(timeout:Timeout, promise:Promise[Any], ex:TimeoutException, parent:Option[ActorRef]) extends Actor {

  def scheduleExpiration = context.system.scheduler.scheduleOnce(2 * timeout.duration) {
    promise failure ex
    context stop self
  }

  var expiration = scheduleExpiration

  def receive = {
    case HeartBeat() =>
      expiration.cancel()
      expiration = scheduleExpiration
      parent match {
        case Some(pRef) =>
          pRef ! HeartBeat()
        case None => ()
      }
    case msg =>
      promise success msg
      expiration.cancel()
      context stop self
  }
}