package ppdm

import akka.actor._
import akka.pattern._

import scala.concurrent.{ExecutionContext, Await, Future}
import scala.concurrent.duration._
import ExecutionContext.Implicits.global

import org.scalatest.FlatSpec
import Constants._

class PPDMSpec extends FlatSpec {
  def assertEqual[T](a1:T, a2:T) = {assert(a1 === a2); 0}

  "A group" should "securely compute totals" in {
    val group = for(i <- 0 until 10) yield Node.spawn("node" + i.toString)
    val direct = Future.traverse(group)(node => node ? GetSecret)
    val all = for {
      groupingFinished <- Future.traverse(group)({node => node ? SetGroup(group)})
      secure <- Future.traverse(group)({node => node ? SecureSum}).mapTo[IndexedSeq[Int]]
      direct <- direct.mapTo[List[Int]]
    } yield direct.reduce(_ + _) :: secure.toList
    Await.result(all, 1 second) reduce(assertEqual(_, _))
  }
}
