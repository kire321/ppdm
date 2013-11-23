package ppdm;

import akka.actor._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Await}
import ExecutionContext.Implicits.global
import scala.collection.JavaConversions.enumerationAsScalaIterator

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
      println(i.getHostAddress());
    }
    println()
  }


  val addresses = enumerationAsScalaIterator(NetworkInterface.getNetworkInterfaces) flatMap {
    i:NetworkInterface => enumerationAsScalaIterator(i.getInetAddresses)}
  val ips = addresses map {_.getHostAddress} filter {str:String => str.count((c:Char) => c.toString == ".") == 3}
  val system = ActorSystem("client")
  val ip = ips.next()
  println(ip)
  val remoteName ="akka.tcp://daemon@" + ip + ":9963/user/node"
  val secret = for {
    remote <- system.actorSelection(remoteName).resolveOne(1 second)
    secret <- remote ? GetSecret()
  } yield secret
  println(Await.result(secret, 1 second))
  system.shutdown()
}
