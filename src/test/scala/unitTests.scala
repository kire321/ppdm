package ppdm

import akka.actor._
import akka.pattern._

import scala.concurrent.{ExecutionContext, Await, Future}
import scala.concurrent.duration._
import ExecutionContext.Implicits.global

import org.scalatest.FlatSpec
import Constants._

class PPDMSpec extends FlatSpec {
  def Group = new {
    val system = ActorSystem("ppdm")
    val nodes = for(i <- 0 until groupSize) yield Node.spawn("node" + i.toString, system)
    Await.ready(Future.traverse(nodes)({node => node ? SetGroup(nodes.toList)}), 1 second)
  }

  "A group" should "securely compute totals" in {
    val group = Group
    val direct = Future.traverse(group.nodes)(node => node ? GetSecret)
    val job = random.nextInt()
    val bothFuture = for {
      secure <- Future.traverse(group.nodes)({node => node ? SecureSum(job)}).mapTo[IndexedSeq[Int]]
      direct <- direct.mapTo[Vector[Int]]
    } yield {println(secure :: direct :: Nil); direct.reduce(_ + _) :: secure.reduce(_ + _) :: Nil}
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
    val sums = Await.result(Future.sequence(futures), 1 second)
    assert(sums.head === sums.last)
    group.system.shutdown()
  }
}
