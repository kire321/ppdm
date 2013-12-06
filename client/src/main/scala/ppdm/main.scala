package ppdm;

import akka.actor._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Await}
import ExecutionContext.Implicits.global
import scala.collection.JavaConversions.enumerationAsScalaIterator
import sys.process._

import ppdm.Constants._
import NewAskPattern.ask

import java.net.NetworkInterface


object Main extends App {

  def getPublicIp = {
    val e=NetworkInterface.getNetworkInterfaces();
    while(e.hasMoreElements()) {
      val n = e.nextElement();
      val ee = n.getInetAddresses();
      while(ee.hasMoreElements()) {
        val i= ee.nextElement();
      }
    }


    val addresses = enumerationAsScalaIterator(NetworkInterface.getNetworkInterfaces) flatMap {
      i:NetworkInterface => enumerationAsScalaIterator(i.getInetAddresses)}
    val ips = addresses map {_.getHostAddress} filter {str:String => str.count((c:Char) => c.toString == ".") == 3}
    ips.next()
  }


  val system = ActorSystem("client")
  val ip = getPublicIp
  println(s"Looking for daemon at $ip")
  println("Summing securely; will timeout in 60 seconds")
  val remoteName = s"akka.tcp://daemon@$ip:9963/user/node"
  val sum = Await.result(for {
    remote <- system.actorSelection(remoteName).resolveOne(1 second)
    grouping <- PatientAsk(remote, Start(), system)
    secureMap <- PatientAsk(remote, TreeSum(), system).mapTo[ActorMap]
    printedReportedBack = println(shortGroup(secureMap.keys.reduce(_ ++ _)))
    //Secure summing sometimes fails for unknown reasons, so this voting hack results in the correct total being selected
    secureGroupSums = secureMap.values map {sums =>
      sums.groupBy(x => x).maxBy((pair:(Int, List[Int])) => pair._2.length)._1
    }
    secureSum = secureGroupSums.fold(0)(_ + _)
  } yield secureSum, 60 seconds)
  system.shutdown()
  println(s"Total is $sum")
  s"echo $sum" #> new java.io.File("sum") !
}
