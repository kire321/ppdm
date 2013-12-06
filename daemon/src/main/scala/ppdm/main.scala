package ppdm;

import akka.actor._

import scala.concurrent.duration._
import scala.concurrent.{Promise, ExecutionContext, Await, Future}
import ExecutionContext.Implicits.global
import collection._
import sys.process._
import util._

import ppdm.Constants._
import NewAskPattern.ask


object Main extends App {

  def host(hostname:String):String = {
    s"host $hostname".!!.replace("\n", "").split(" ").last
  }

  def associateWithPeers(peers:List[String], numPeers:Int):Future[List[ActorRef]] = {
    if (numPeers == 0 || peers.isEmpty) {
      return Future.successful(Nil)
    } else {
      val remoteName = s"akka.tcp://daemon@${peers.head}:9963/user/node"
      val promise = Promise[List[ActorRef]]
      system.actorSelection(remoteName).resolveOne(1 second) onComplete {
        case Success(peer) =>
          println(s"Successful association with $peer")
          promise completeWith (associateWithPeers(peers.tail, numPeers - 1) map (peer :: _))
        case Failure(e) =>
          println(s"Connection to $remoteName failed with ${e.getMessage}")
          promise completeWith associateWithPeers(peers.tail, numPeers)
      }
      promise.future
    }
  }

  require(args.length == 1, s"You must provide exactly one argument (a shell command which should output the secret). Got $args")
  val system = ActorSystem("daemon")
  val hostname = Seq("bash", "-c", "echo $HOSTNAME").!!.replace("\n", "")
  println(s"Hostname: $hostname")
  val secretStr = args(0).!!.replace("\n", "")
  println(s"Secret: $secretStr")
  val node = system.actorOf(Props(new Node {
    override val secret = secretStr.toInt
  }), name = hostname)
  val file = io.Source.fromFile(s"$hostname/peers.list")
  val peers = file.getLines().filter(_ != hostname).toList map host
  file.close()
  println(peers)
  for {
    aRefs <- associateWithPeers(peers, 3)
    debug <- node ? Debug()
    neighborsAdded <- Future.traverse(aRefs)(node ? AddNeighbor(_))
    neighborsAdded <- Future.traverse(aRefs)(_ ? AddNeighbor(node))
  } yield println(s"Ready to go with ${aRefs.size} neighbors")
  Thread.currentThread().interrupt()
}
