package ppdm

import akka.actor._

import scala.concurrent.{ExecutionContext, Await, Future}
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import collection._

import ppdm.Constants._
import NewAskPattern.ask

object Fixtures {

  def Group(factory:Factories.Factory = Node.spawn _) = new {
    val system = ActorSystem("ppdm")
    val nodes = for(i <- 0 until groupSize) yield factory("node" + i.toString, system)
  }

  def FixedDegreeRandomGraph(size:Int, degree:Int, factory:Factories.Factory = Node.spawn _) = new {
    require(degree.toFloat / 2 == degree / 2 , "The degree must be even")
    val system = ActorSystem("ppdm")
    val nodes = for(i <- 0 until size) yield factory("node" + i.toString, system)
    val repeatedNodes = List.fill(degree / 2)(List(nodes.toSeq: _*)).flatten
    val pairs = repeatedNodes zip random.shuffle(repeatedNodes) filter (pair => pair._1 != pair._2)
    def doubleLink(pair:(ActorRef, ActorRef)) = {Future.sequence((pair._1 ? AddNeighbor(pair._2)) :: (pair._2 ? AddNeighbor(pair._1)) :: Nil)}
    Await.result(Future.traverse(pairs)(doubleLink), 2 seconds)
  }
}

object Tests {

  def secureSum(factory:Factories.Factory = Node.spawn _) = {
    val group = Fixtures.Group(factory)
    val both = Await.result(for {
      secure <- Node.secureSumWithRetry(group.nodes, group.system)
      direct <- SafeFuture.fold(group.nodes map {node:ActorRef => (node ? GetSecret()).mapTo[Int]})(0)(_ + _)
    } yield (secure, direct), 10 seconds)
    assert(both._1 == both._2)
    group.system.shutdown()
  }


  def grouping(size: Int = 500, degree: Int = 6, factory:Factories.Factory = Node.spawn _, hook:Hooks.Hook = {(x:IndexedSeq[ActorRef]) => ()}, timeoutMultiple:Int = 1) = {
    val graph = Fixtures.FixedDegreeRandomGraph(size, degree, factory)
    hook(graph.nodes)
    Await.result(PatientAsk(graph.nodes.head, Start(), graph.system), 10*timeoutMultiple seconds)
    val redundantGroups = Await.result(SafeFuture.traverse(graph.nodes)(_ ? GetGroup()), 5*timeoutMultiple seconds)
    val groups = redundantGroups.asInstanceOf[Vector[mutable.Set[ActorRef]]].distinct
    //println(groups map {_ size})
    val nodesFromGroups = groups flatMap {elem => elem}
    val distinctNodes = nodesFromGroups.distinct
    assert(distinctNodes.length == nodesFromGroups.length, "Nodes are in at most one group")
    assert(distinctNodes.length == graph.nodes.length, "Nodes are in at least one group")
    groups map {_.size} foreach {length =>
      val willPass = .5 * groupSize <= length && length <= groupSize *3
      if (!willPass)
        println("Length " + length.toString + " will fail test")
      assert(willPass, "Group is the correct size.")
    }
    graph.system.shutdown()
  }

  def treeSum(size: Int = 500, degree: Int = 6, factory:Factories.Factory = Node.spawn _, hook:Hooks.Hook = {(x:IndexedSeq[ActorRef]) => ()}, timeoutMultiple:Int = 1) = {
    val graph = Fixtures.FixedDegreeRandomGraph(size, degree, factory)
    hook(graph.nodes)
    val finished = for {
      grouping <- PatientAsk(graph.nodes.head, Start(), graph.system)
      secureMap <- PatientAsk(graph.nodes.head, TreeSum(), graph.system).mapTo[ActorMap]
      printedReportedBack = println(shortGroup(secureMap.keys.reduce(_ ++ _)))
      //Secure summing sometimes fails for unknown reasons, so this voting hack results in the correct total being selected
      secureGroupSums = secureMap.values map {sums =>
        sums.groupBy(x => x).maxBy((pair:(Int, List[Int])) => pair._2.length)._1
      }
      secureSum = secureGroupSums.fold(0)(_ + _)
      stillAlive <- SafeFuture.traverse(graph.nodes){node => (node ? Ping()) map (_ => node)}
      printedStillAlive = println(shortGroup(stillAlive))
      insecureSum <- SafeFuture.reduce(graph.nodes map {node => (node ? GetSecret()).mapTo[Int]})(_ + _)
    } yield {
      println(s"$secureSum, $insecureSum")
      assert(secureSum == insecureSum, "Secure and insecure sums should be equal")
    }
    Await.result(finished, 20*timeoutMultiple seconds)
    graph.system.shutdown()
  }
}

object Hooks {
  type Hook = (IndexedSeq[ActorRef] => Unit)

  def prepRoot(nodes:IndexedSeq[ActorRef]):Unit = {
    val finished = for {
      immune <- nodes.head ? SetImmune()
      debug <- nodes.head ? Debug()
    } yield debug
    Await.result(finished, 1 second)
  }

  def debug(nodes:IndexedSeq[ActorRef]):Unit = {
    Await.result(nodes.head ? Debug(), 1 second)
  }

  def immuneRoot(nodes:IndexedSeq[ActorRef]):Unit = {
    Await.result(nodes.head ? SetImmune(), 1 second)
  }
}

object Factories {

  type Factory = (String, ActorSystem) => ActorRef

  def passThrough(name:String, system:ActorSystem) = {
    system.actorOf(Props(new FallableNode({() => 0}, 0, 100)), name = name)
  }

  def dyingNodesForGroup(name:String, system:ActorSystem) = {
    system.actorOf(Props(new FallableNode(() => 0, .1, 5)), name = name)
  }

  def dyingNodesForGraph(name:String, system:ActorSystem) = {
    system.actorOf(Props(new FallableNode(() => 0, .1, 100)), name = name)
  }

  def powerLaw(start:Double, stop:Double, exponent:Double):(() => Double) = {
    def innerFunc:Double = {
      Math.pow(((Math.pow(stop, (exponent+1)) - Math.pow(start, (exponent+1)))*random.nextFloat() + Math.pow(start, (exponent+1))), (1/(exponent+1)))
    }
    innerFunc _
  }

  def typicalLatency = powerLaw(100, 1000, -1.5)().toInt

  def latentNodes(name:String, system:ActorSystem) = {
    system.actorOf(Props(new FallableNode(typicalLatency _, 0, 100)), name = name)
  }
}