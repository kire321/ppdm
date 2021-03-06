package ppdm

import akka.actor._

import scala.concurrent.{ExecutionContext, Await, Future}
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import collection._

import org.scalatest.FlatSpec
import ppdm.Constants._
import NewAskPattern.ask

class PPDMSpec extends FlatSpec {

  "A group" should "sum securely" in Tests.secureSum()

  it should "sum in parallel" in {
    val group = Fixtures.Group()
    val futures = (0 until 2) map {_ =>
      val msg = StartSecureSum(random.nextInt(), group.nodes)
      val partialSums = group.nodes map {node => PatientAsk(node, msg, group.system).mapTo[Int]}
      Future.reduce(partialSums)(_ + _)
    }
    val sums = Await.result(Future.sequence(futures), 5 second)
    assert(sums.head === sums.last)
    group.system.shutdown()
  }

  "A Fixed-Degree Random Graph" should "have the size and degree we asked for" in {
    val size = 200
    val degree = 4
    val tolerance = .9
    val graph = Fixtures.FixedDegreeRandomGraph(size, degree)
    assert(graph.nodes.length == size)
    val neighborSets = Await.result(Future.traverse(graph.nodes)(_ ? GetNeighbors()), 1 second).asInstanceOf[Vector[Set[ActorRef]]]
    //println(neighborSets map (_.size))
    //TODO: how is it possible that we sometimes get degree 3 nodes?
    assert(neighborSets.count(_.size == degree) > tolerance * size)
    graph.system.shutdown()
  }

  //it should "form groups" in Tests.grouping(hook = Hooks.debug _)

  //it should "treeSum" in Tests.treeSum()

  //"Pass-through fallableNodes" should "treeSum" in Tests.treeSum(size = 100, factory = Factories.passThrough _)

  //"Latent nodes" should "form groups" in Tests.grouping(factory = Factories.latentNodes _, timeoutMultiple = 5, hook = Hooks.prepRoot _)

  //"Latent nodes" should "treeSum" in Tests.treeSum(factory = Factories.latentNodes _, timeoutMultiple = 5, hook = Hooks.prepRoot _)

  //"Latent nodes" should "sum securely" in Tests.secureSum(Factories.latentNodes _)

  //"Dying nodes" should "form groups" in Tests.grouping(factory = Factories.dyingNodesForGraph _, hook = Hooks.prepRoot _, timeoutMultiple = 2)

  "Dying nodes" should "treeSum" in Tests.treeSum(factory = Factories.dyingNodesForGraph _, hook = Hooks.immuneRoot _, timeoutMultiple = 2)

  "Dying nodes" should "sum securely" in Tests.secureSum(Factories.dyingNodesForGroup _)

}
