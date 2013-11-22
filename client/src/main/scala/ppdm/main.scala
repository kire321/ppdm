package ppdm;

import akka.actor._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Await, Future}
import ExecutionContext.Implicits.global

import ppdm.Constants._
import NewAskPattern.ask

object Main extends App {
  val system = ActorSystem("client")
  val secret = for {
    remote <- system.actorSelection("akka.tcp://daemon@127.0.1.1:9963/user/node").resolveOne(1 second)
    secret <- remote ? GetSecret()
  } yield secret
  println(Await.result(secret, 1 second))
  system.shutdown()
}
