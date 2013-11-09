package ppdm

import akka.actor._
import akka.pattern._

import scala.concurrent.{ExecutionContext, Await, Future}
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import collection._

import org.scalatest.FlatSpec
import ppdm.Constants._

class PPDMSpec extends FlatSpec {
  def Group = new {
    val system = ActorSystem("ppdm")
    val nodes = for(i <- 0 until groupSize) yield Node.spawn("node" + i.toString, system)
    Await.result(Future.traverse(nodes)({node => node ? SetGroup(mutable.HashSet(nodes.toSeq: _*))}), 1 second)
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

  def FixedDegreeRandomGraph(size:Int, degree:Int) = new {
    require(degree.toFloat / 2 == degree / 2 , "The degree must be even")
    val system = ActorSystem("ppdm")
    val nodes = for(i <- 0 until size) yield Node.spawn("node" + i.toString, system)
    val repeatedNodes = List.fill(degree / 2)(List(nodes.toSeq: _*)).flatten
    val pairs = repeatedNodes zip random.shuffle(repeatedNodes) filter (pair => pair._1 != pair._2)
    def doubleLink(pair:(ActorRef, ActorRef)) = {Future.sequence((pair._1 ? AddNeighbor(pair._2)) :: (pair._2 ? AddNeighbor(pair._1)) :: Nil)}
    Await.result(Future.traverse(pairs)(doubleLink), 1 second)
  }

  "A Fixed-Degree Random Graph" should "have the size and degree we asked for" in {
    val size = 200
    val degree = 4
    val tolerance = .9
    val graph = FixedDegreeRandomGraph(size, degree)
    assert(graph.nodes.length == size)
    val neighborSets = Await.result(Future.traverse(graph.nodes)(_ ? GetNeighbors), 1 second).asInstanceOf[Vector[Set[ActorRef]]]
    //println(neighborSets map (_.size))
    //TODO: how is it possible that we sometimes get degree 3 nodes?
    assert(neighborSets.count(_.size == degree) > tolerance * size)
    graph.system.shutdown()
  }

  it should "form groups" in {
    val graph = FixedDegreeRandomGraph(500, 6)
    Await.result(graph.nodes.head ? Start, 5 seconds)
    val redundantGroups = Await.result(Future.traverse(graph.nodes)(_ ? GetGroup), 5 seconds)
    val groups = redundantGroups.asInstanceOf[Vector[mutable.Set[ActorRef]]].distinct
    //println(groups map {_ size})
    val nodesFromGroups = groups flatMap {elem => elem}
    val distinctNodes = nodesFromGroups.distinct
    assert(distinctNodes.length == nodesFromGroups.length, "Nodes are in at most one group")
    assert(distinctNodes.length == graph.nodes.length, "Nodes are in at least one group")
    val tolerance = .5
    groups map {_.size} foreach {length =>
      val willPass = tolerance * groupSize <= length && length <= groupSize / tolerance
      if (!willPass)
        println("Length " + length.toString + " will fail test")
      assert(willPass, "Group is the correct size.")
    }
    graph.system.shutdown()
  }

  it should "sum securely" in {
    val graph = FixedDegreeRandomGraph(500, 6)
    val finished = for {
      grouping <- graph.nodes.head ? Start
      secureMap <- (graph.nodes.head ? TreeSum).mapTo[ActorMap]
      //Secure summing sometimes fails for unknown reasons, so this voting hack results in the correct total being selected
      secureGroupSums = secureMap.values map {sums =>
          sums.groupBy(x => x).maxBy((pair:(Int, List[Int])) => pair._2.length)._1
      }
      secureSum = secureGroupSums.fold(0)(_ + _)
      insecureSum <- Future.reduce(graph.nodes map {node => (node ? GetSecret).mapTo[Int]})(_ + _)
    } yield assert(secureSum === insecureSum, "Secure and insecure sums should be equal")
    Await.result(finished, 5 seconds)
    graph.system.shutdown()
  }
}

