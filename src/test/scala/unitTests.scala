package ppdm

import akka.actor._
import akka.pattern._

import scala.concurrent.{ExecutionContext, Await, Future}
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import collection._

import org.scalatest.FlatSpec
import Constants._

class PPDMSpec extends FlatSpec {
  def Group = new {
    val system = ActorSystem("ppdm")
    val nodes = for(i <- 0 until groupSize) yield Node.spawn("node" + i.toString, system)
    Await.ready(Future.traverse(nodes)({node => node ? SetGroup(mutable.Set(nodes.toSeq: _*))}), 1 second)
  }

  "A group" should "securely compute totals" in {
    val group = Group
    val direct = Future.traverse(group.nodes)(node => node ? GetSecret)
    val job = random.nextInt()
    val bothFuture = for {
      secure <- Future.traverse(group.nodes)({node => node ? SecureSum(job)}).mapTo[IndexedSeq[Int]]
      direct <- direct.mapTo[Vector[Int]]
    } yield direct.reduce(_ + _) :: secure.reduce(_ + _) :: Nil
    val both = Await.result(bothFuture, 1 second)
    assert(both.head === both.last)
    group.system.shutdown()
  }

  it should "sum in parallel" in {
    val group = Group
    val futures = (0 until 2) map {_ =>
      val msg = SecureSum(random.nextInt())
      val partialSums = group.nodes map {node => (node ? msg).mapTo[Int]}
      Future.reduce(partialSums)(_ + _)
    }
    val sums = Await.result(Future.sequence(futures), 5 second)
    assert(sums.head === sums.last)
    group.system.shutdown()
  }

  def ERgraph(size:Int, targetDegree:Float) = new {
    val system = ActorSystem("ppdm")
    val nodes = for(i <- 0 until size) yield Node.spawn("node" + i.toString, system)
    //Await.ready(nodes.head ? Debug, 1 second)
    val linkP = (targetDegree / size) / 2
    def cartesianProduct[A, B](left:List[A], right:List[B]) = {left flatMap {leftElem => right map {rightElem => (leftElem, rightElem)}}}
    val noSelfLoops = cartesianProduct(nodes.toList, nodes.toList) filter (pair => pair._1 != pair._2)
    val pairs = noSelfLoops filter (pair => random.nextFloat() < linkP)
    def doubleLink(pair:(ActorRef, ActorRef)) = {Future.sequence((pair._1 ? AddNeighbor(pair._2)) :: (pair._2 ? AddNeighbor(pair._1)) :: Nil)}
    Await.ready(Future.traverse(pairs)(doubleLink), 1 second)
  }

  "An Erdos-Renyi Random Graph" should "have the size and degree we asked for" in {
    val size = 100
    val degree = 5
    val graph = ERgraph(size, degree)
    val tolerance = .7
    assert(graph.nodes.length == size)
    val totalDeg = Future.reduce(graph.nodes map (node => (node ? GetDegree).mapTo[Int]))(_ + _)
    val avgDeg = Await.result(totalDeg, 1 second).asInstanceOf[Float] / size
    //println(avgDeg)
    assert(degree * tolerance < avgDeg)
    assert(avgDeg < degree / tolerance)
    graph.system.shutdown()
  }


  it should "form groups" in {
    val graph = ERgraph(200, 5)
    Await.ready(graph.nodes.head ? Debug, 1 second)
    Await.ready(graph.nodes.head ? Start, 30 seconds)
    //println(Await.result(Future.traverse(graph.nodes)(_ ? GetGroupID), 1 second).asInstanceOf[Vector[Int]])
    val redundantGroups = Await.result(Future.traverse(graph.nodes)(_ ? GetGroup), 5 second)
    val groups = redundantGroups.asInstanceOf[Vector[mutable.Set[ActorRef]]].distinct
    println(groups map {_ size})
    val nodesFromGroups = groups flatMap {elem => elem}
    val distinctNodes = nodesFromGroups.distinct
    assert(distinctNodes.length == nodesFromGroups.length, "Nodes are in at most one group")
    assert(distinctNodes.length == graph.nodes.length, "Nodes are in at least one group")
    val tolerance = .7
    groups map {_.size} foreach {length =>
      assert(tolerance * groupSize < length)
      assert(length < groupSize / tolerance)
    }
    graph.system.shutdown()
  }
}
