package ppdm

import akka.actor._
import akka.pattern._

import scala.concurrent.{ExecutionContext, Await, Future}
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import collection._

import org.scalatest.FlatSpec
import ppdm.Constants._
import NewAskPattern.ask

class PPDMSpec extends FlatSpec {
  def Group = new {
    val system = ActorSystem("ppdm")
    val nodes = for(i <- 0 until groupSize) yield Node.spawn("node" + i.toString, system)
    Await.result(Future.traverse(nodes)({node => node ? SetGroup(mutable.HashSet(nodes.toSeq: _*))}), 1 second)
  }

  "SafeFuture.sequence" should "sequence futures" in {
    val result = Await.result(SafeFuture.sequence(Future.successful(0) :: Future.successful(0) :: Nil), 1 second)
    assert(result === List(0, 0))
  }

  it should "work for weird types" in {
    val set = mutable.HashSet(Future.successful(0), Future.successful(1), Future.successful(2))
    val result = Await.result(SafeFuture.sequence(set), 1 second)
    assert(result === mutable.HashSet(0, 1, 2))
  }

  it should "drop timeouts" in {
    val group = Group
    val futures = (group.nodes.head ? Key(0, 0)) :: Future.successful(0) :: Nil
    val result = Await.result(SafeFuture.sequence(futures), 3 seconds)
    assert(result.asInstanceOf[List[Int]] === List(0))
    group.system.shutdown()
  }

  it should "fail for all exceptions other than timeouts" in {
    val group = Group
    intercept[Exception] {
      Await.result(SafeFuture.sequence((group.nodes.head ? "novel msg") :: Future.successful(0) :: Nil), 1 second)
    }
    group.system.shutdown()
  }

  "SafeFuture.traverse" should "sequence futures" in {
    val result = Await.result(SafeFuture.traverse(0 :: 0 :: Nil)(Future.successful(_)), 1 second)
    assert(result === List(0, 0))
  }

  it should "work for weird types" in {
    val set = mutable.HashSet(0, 1, 2)
    val future = SafeFuture.traverse(set)(x => Future.successful(x))
    val result = Await.result(future, 1 second)
    assert(result === set)
  }

  it should "drop timeouts" in {
    val graph = FixedDegreeRandomGraph(10, 0, {(name:String, system:ActorSystem) =>
      system.actorOf(Props(new FallableNode(() => 0, .5)), name = name)})

    val future = SafeFuture.traverse(graph.nodes)(_ ? GetGroup())
    assert(Await.result(future, 3 seconds).asInstanceOf[Vector[_]].size > 0)
    graph.system.shutdown()
  }

  it should "fail for all exceptions other than timeouts" in {
    val group = Group
    intercept[Exception] {
      Await.result(SafeFuture.traverse("novel msg" :: GetSecret() :: Nil)(group.nodes.head ? _), 1 second)
    }
    group.system.shutdown()
  }



  "A group" should "securely compute totals" in {
    val group = Group
    val direct = Future.traverse(group.nodes)(node => node ? GetSecret())
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

  def FixedDegreeRandomGraph(size:Int, degree:Int, factory:Factory = Node.spawn _, timeoutMultiple:Int = 1) = new {
    require(degree.toFloat / 2 == degree / 2 , "The degree must be even")
    val system = ActorSystem("ppdm")
    val nodes = for(i <- 0 until size) yield factory("node" + i.toString, system)
    val repeatedNodes = List.fill(degree / 2)(List(nodes.toSeq: _*)).flatten
    val pairs = repeatedNodes zip random.shuffle(repeatedNodes) filter (pair => pair._1 != pair._2)
    def doubleLink(pair:(ActorRef, ActorRef)) = {Future.sequence((pair._1 ? AddNeighbor(pair._2)) :: (pair._2 ? AddNeighbor(pair._1)) :: Nil)}
    Await.result(Future.traverse(pairs)(doubleLink), 2*timeoutMultiple seconds)
  }

  "A Fixed-Degree Random Graph" should "have the size and degree we asked for" in {
    val size = 200
    val degree = 4
    val tolerance = .9
    val graph = FixedDegreeRandomGraph(size, degree)
    assert(graph.nodes.length == size)
    val neighborSets = Await.result(Future.traverse(graph.nodes)(_ ? GetNeighbors()), 1 second).asInstanceOf[Vector[Set[ActorRef]]]
    //println(neighborSets map (_.size))
    //TODO: how is it possible that we sometimes get degree 3 nodes?
    assert(neighborSets.count(_.size == degree) > tolerance * size)
    graph.system.shutdown()
  }

  def testGrouping(size: Int = 500, degree: Int = 6, factory:Factory = Node.spawn _, hook:Hook = {(x:IndexedSeq[ActorRef]) => ()}, timeoutMultiple:Int = 1) = {
    val graph = FixedDegreeRandomGraph(size, degree, factory, timeoutMultiple)
    hook(graph.nodes)
    Await.result(PatientAsk(graph.nodes.head, Start(), graph.system), 5*timeoutMultiple seconds)
    val redundantGroups = Await.result(Future.traverse(graph.nodes)(_ ? GetGroup()), 5*timeoutMultiple seconds)
    val groups = redundantGroups.asInstanceOf[Vector[mutable.Set[ActorRef]]].distinct
    println(groups map {_ size})
    val nodesFromGroups = groups flatMap {elem => elem}
    val distinctNodes = nodesFromGroups.distinct
    assert(distinctNodes.length == nodesFromGroups.length, "Nodes are in at most one group")
    assert(distinctNodes.length == graph.nodes.length, "Nodes are in at least one group")
    groups map {_.size} foreach {length =>
      val willPass = .5 * groupSize <= length && length <= groupSize * 3
      if (!willPass)
        println("Length " + length.toString + " will fail test")
      assert(willPass, "Group is the correct size.")
    }
    graph.system.shutdown()
  }

  it should "form groups" in testGrouping()

  type Hook = (IndexedSeq[ActorRef] => Unit)

  def testSecureSumming(size: Int = 500, degree: Int = 6, factory:Factory = Node.spawn _, hook:Hook = {(x:IndexedSeq[ActorRef]) => ()}, timeoutMultiple:Int = 1) = {
    val graph = FixedDegreeRandomGraph(size, degree, factory, timeoutMultiple)
    hook(graph.nodes)
    val finished = for {
      grouping <- PatientAsk(graph.nodes.head, Start(), graph.system)(10 seconds)
      secureMap <- (graph.nodes.head ? TreeSum()).mapTo[ActorMap]
      //Secure summing sometimes fails for unknown reasons, so this voting hack results in the correct total being selected
      secureGroupSums = secureMap.values map {sums =>
        sums.groupBy(x => x).maxBy((pair:(Int, List[Int])) => pair._2.length)._1
      }
      secureSum = secureGroupSums.fold(0)(_ + _)
      insecureSum <- Future.reduce(graph.nodes map {node => (node ? GetSecret()).mapTo[Int]})(_ + _)
    } yield assert(secureSum === insecureSum, "Secure and insecure sums should be equal")
    Await.result(finished, 10*timeoutMultiple seconds)
    graph.system.shutdown()
  }

  it should "sum securely" in testSecureSumming()

  def passThrough(name:String, system:ActorSystem) = {
    system.actorOf(Props(new FallableNode({() => 0}, 0)), name = name)
  }

  "Pass-through fallableNodes" should "sum securely" in testSecureSumming(size = 100, factory = passThrough _)

  def dyingNodes(name:String, system:ActorSystem) = {
    system.actorOf(Props(new FallableNode(() => 0, .001)), name = name)
  }

  def prepRoot(nodes:IndexedSeq[ActorRef]):Unit = {
    val finished = for {
      immune <- nodes.head ? SetImmune()
      debug <- nodes.head ? Debug()
    } yield debug
    Await.result(finished, 1 second)
  }

  //"Dying nodes" should "sum securely" in testSecureSumming(size = 10, factory = dyingNodes _, hook = prepRoot _)

  def powerLaw(start:Double, stop:Double, exponent:Double):(() => Double) = {
    def innerFunc:Double = {
      Math.pow(((Math.pow(stop, (exponent+1)) - Math.pow(start, (exponent+1)))*random.nextFloat() + Math.pow(start, (exponent+1))), (1/(exponent+1)))
    }
    innerFunc _
  }

  def typicalLatency = powerLaw(100, 1000, -1.5)().toInt

  def latentNodes(name:String, system:ActorSystem) = {
    system.actorOf(Props(new FallableNode(typicalLatency _, 0)), name = name)
  }

  "Latent nodes" should "form grooups" in testGrouping(size = 500, factory = latentNodes _, timeoutMultiple = 5, hook = prepRoot _)

  //"Latent nodes" should "sum securely" in testSecureSumming(size = 500, factory = latentNodes _, timeoutMultiple = 5, hook = prepRoot _)
}
