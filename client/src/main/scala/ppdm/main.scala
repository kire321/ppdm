package ppdm;

import akka.actor._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Await}
import ExecutionContext.Implicits.global

import ppdm.Constants._
import NewAskPattern.ask

import java.net.NetworkInterface


object Main extends App {
  val e=NetworkInterface.getNetworkInterfaces();
  while(e.hasMoreElements())
  {
    val n = e.nextElement();
    val ee = n.getInetAddresses();
    while(ee.hasMoreElements())
    {
      val i= ee.nextElement();
      System.out.println(i.getHostAddress());
    }
  }

  val system = ActorSystem("client")
  val ip = NetworkInterface.getNetworkInterfaces.nextElement().getInetAddresses.nextElement().getHostAddress
  val remoteName ="akka.tcp://daemon@" + ip + ":9963/user/node"
  val secret = for {
    remote <- system.actorSelection(remoteName).resolveOne(1 second)
    secret <- remote ? GetSecret()
  } yield secret
  println(Await.result(secret, 1 second))
  system.shutdown()
}
