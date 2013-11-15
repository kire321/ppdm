package ppdm;

import java.util.concurrent.TimeoutException

import scala.concurrent.{Future, Promise}
import scala.util.Failure
import scala.util.Success
import scala.concurrent.ExecutionContext
import scala.collection._
import scala.collection.generic.CanBuildFrom

object SafeFuture {

  def sequence[A, M[_] <: TraversableOnce[_]](in: M[Future[A]])(implicit cbf: CanBuildFrom[M[Future[A]], A, M[A]], executor: ExecutionContext): Future[M[A]] = {
    in.foldLeft(Future.successful(cbf(in)))((leftFuture, item) => {
      val promise = Promise[mutable.Builder[A, M[A]]]()
      val rightFuture = item.asInstanceOf[Future[A]]
      leftFuture onComplete {
        case Success(builder) =>
          rightFuture onComplete {
            case Success(a) =>
              builder += a
              promise success builder
            case Failure(e:TimeoutException) =>
              promise success builder
            case Failure(e) =>
              promise failure e
          }
        case Failure(e) =>
          promise failure e
      }
      promise.future
    }) map (builder => builder.result())
  }

  //Way to do this without code repetition? The stdlib repeats itself, so maybe not.
  def traverse[A, B, M[_] <: TraversableOnce[_]](in:M[A])(func:A => Future[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]], executor: ExecutionContext): Future[M[B]] = {
    in.foldLeft(Future.successful(cbf(in)))((leftFuture, item) => {
      val promise = Promise[mutable.Builder[B, M[B]]]()
      val rightFuture = func(item.asInstanceOf[A])
      leftFuture onComplete {
        case Success(builder) =>
          rightFuture onComplete {
            case Success(a) =>
              builder += a
              promise success builder
            case Failure(e:TimeoutException) =>
              promise success builder
            case Failure(e) =>
              promise failure e
          }
        case Failure(e) =>
          promise failure e
      }
      promise.future
    }) map (builder => builder.result())
  }
}