package ppdm

import akka.actor._

import scala.concurrent.{ExecutionContext, Await, Future}
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import collection._

import org.scalatest.FlatSpec
import ppdm.Constants._
import NewAskPattern.ask

class SafeFutureSpec extends FlatSpec {

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
    val group = Fixtures.Group
    val futures = (group.nodes.head ? Key(0, 0)) :: Future.successful(0) :: Nil
    val result = Await.result(SafeFuture.sequence(futures), 3 seconds)
    assert(result.asInstanceOf[List[Int]] === List(0))
    group.system.shutdown()
  }

  it should "fail for all exceptions other than timeouts" in {
    val group = Fixtures.Group
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
    val graph = Fixtures.FixedDegreeRandomGraph(10, 0, {(name:String, system:ActorSystem) =>
      system.actorOf(Props(new FallableNode(() => 0, .5)), name = name)})

    val future = SafeFuture.traverse(graph.nodes)(_ ? GetGroup())
    assert(Await.result(future, 3 seconds).asInstanceOf[Vector[_]].size > 0)
    graph.system.shutdown()
  }

  it should "fail for all exceptions other than timeouts" in {
    val group = Fixtures.Group
    intercept[Exception] {
      Await.result(SafeFuture.traverse("novel msg" :: GetSecret() :: Nil)(group.nodes.head ? _), 1 second)
    }
    group.system.shutdown()
  }
}