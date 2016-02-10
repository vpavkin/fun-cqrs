package io.funcqrs

import io.funcqrs.Proj.{ OrElsePrj, AndThenPrj }
import io.funcqrs.Projection._

import scala.concurrent.Future
import scala.util.control.NonFatal

import io.funcqrs.interpreters.Monads._

trait Prj[F[_]] {

  type HandleEvent = PartialFunction[DomainEvent, F[Unit]]
  type HandleFailure = PartialFunction[(DomainEvent, Throwable), F[Unit]]

  def handleEvent: HandleEvent

  def handleFailure: HandleFailure = PartialFunction.empty

  final def onEvent(evt: DomainEvent)(implicit monadOps: MonadOps[F]): F[Unit] = {
    if (handleEvent.isDefinedAt(evt)) {
      monad(handleEvent(evt)).recoverWith {
        case NonFatal(exp) if handleFailure.isDefinedAt((evt, exp)) => handleFailure(evt, exp)
      }
    } else {
      monadOps.pure(())
    }
  }

  /**
   * Builds a [[io.funcqrs.Prj.AndThenPrj]] composed of this Prj and the passed Prj.
   *
   * [[DomainEvent]]s will be send to both projections. One after the other starting by this followed by the passed Prj.
   *
   * NOTE: In the occurrence of any failure on any of the underling Prjs, this Prj may be replayed,
   * therefore idempotent operations are recommended.
   */
  def andThen(projection: Prj[F]) = new AndThenPrj(this, projection)

  /**
   * Builds a [[io.funcqrs.Prj.OrElsePrj]]composed of this Prj and the passed Prj.
   *
   * If this Prj is defined for a given incoming [[DomainEvent]], then this Prj will be applied,
   * otherwise we fallback to the passed Prj.
   */
  def orElse(fallbackPrj: Prj[F]) = new OrElsePrj(this, fallbackPrj)
}

object Proj {

  /** Prj with empty domain */
  def empty[F[_]] = new Prj[F] {
    def handleEvent: HandleEvent = PartialFunction.empty
  }

  /**
   * A [[Prj]] composed of two other Prjs to each [[DomainEvent]] will be sent.
   *
   * Note that the second Prj is only applied once the first is completed successfully.
   *
   * In the occurrence of any failure on any of the underling Prjs, this Prj may be replayed,
   * therefore idempotent operations are recommended.
   *
   * If none of the underlying Prjs is defined for a given DomainEvent,
   * then this Prj is considered to be not defined for this specific DomainEvent.
   * As such a [[AndThenPrj]] can be combined with a [[OrElsePrj]].
   *
   * For example:
   * {{{
   *   val projection1 : Prj = ...
   *   val projection2 : Prj = ...
   *   val projection3 : Prj = ...
   *
   *   val finalPrj = (projection1 andThen projection2) orElse projection3
   *
   *   finalPrj.onEvent(SomeEvent("abc"))
   *   // if SomeEvent("abc") is not defined for projection1 nor for projection2, projection3 will be applied
   * }}}
   *
   */
  private[funcqrs] class AndThenPrj[F[_]](firstProj: Prj[F], secondProj: Prj[F]) extends ComposedPrj(firstProj, secondProj) with Prj[F] {

    import scala.concurrent.ExecutionContext.Implicits.global

    implicit val monadOps: MonadOps[F] = ???

    val projections = Seq(firstProj, secondProj)

    def handleEvent: HandleEvent = {
      // note that we only broadcast if at least one of the underlying
      // projections is defined for the incoming event
      // as such we make it possible to compose using orElse
      case domainEvent if composedHandleEvent.isDefinedAt(domainEvent) =>
        // send event to all projections
        monad(firstProj.onEvent(domainEvent)).flatMap { _ =>
          secondProj.onEvent(domainEvent)
        }
    }
  }

  /**
   * A [[Prj]] composed of two other Prjs.
   *
   * Its `receiveEvent` is defined in terms of the `receiveEvent` method form the first Prj
   * with fallback to the `receiveEvent` method of the second Prj.
   *
   * As such the second Prj is only applied if the first Prj is not defined
   * for the given incoming [[DomainEvent]]
   *
   */
  private[funcqrs] class OrElsePrj[F[_]](firstProj: Prj[F], secondProj: Prj[F]) extends ComposedPrj(firstProj, secondProj) with Prj[F] {
    def handleEvent = composedHandleEvent
  }

  private[funcqrs] class ComposedPrj[F[_]](firstProj: Prj[F], secondProj: Prj[F]) {
    // compose underlying receiveEvents PartialFunction in order
    // to decide if this Prj is defined for given incoming DomainEvent
    private[funcqrs] def composedHandleEvent = firstProj.handleEvent orElse secondProj.handleEvent
  }

}

trait Projection {

  type HandleFailure = PartialFunction[(DomainEvent, Throwable), Future[Unit]]

  def handleEvent: HandleEvent

  def handleFailure: HandleFailure = PartialFunction.empty

  final def onEvent(evt: DomainEvent): Future[Unit] = {
    if (handleEvent.isDefinedAt(evt)) {
      import scala.concurrent.ExecutionContext.Implicits.global
      handleEvent(evt).recoverWith {
        case NonFatal(exp) if handleFailure.isDefinedAt((evt, exp)) => handleFailure(evt, exp)
      }
    } else {
      Future.successful(())
    }
  }

  /**
   * Builds a [[AndThenProjection]] composed of this Projection and the passed Projection.
   *
   * [[DomainEvent]]s will be send to both projections. One after the other starting by this followed by the passed Projection.
   *
   * NOTE: In the occurrence of any failure on any of the underling Projections, this Projection may be replayed,
   * therefore idempotent operations are recommended.
   */
  def andThen(projection: Projection) = new AndThenProjection(this, projection)

  /**
   * Builds a [[OrElseProjection]]composed of this Projection and the passed Projection.
   *
   * If this Projection is defined for a given incoming [[DomainEvent]], then this Projection will be applied,
   * otherwise we fallback to the passed Projection.
   */
  def orElse(fallbackProjection: Projection) = new OrElseProjection(this, fallbackProjection)
}

object Projection {

  /** Projection with empty domain */
  def empty = new Projection {
    def handleEvent: HandleEvent = PartialFunction.empty
  }

  /**
   * A [[Projection]] composed of two other Projections to each [[DomainEvent]] will be sent.
   *
   * Note that the second Projection is only applied once the first is completed successfully.
   *
   * In the occurrence of any failure on any of the underling Projections, this Projection may be replayed,
   * therefore idempotent operations are recommended.
   *
   * If none of the underlying Projections is defined for a given DomainEvent,
   * then this Projection is considered to be not defined for this specific DomainEvent.
   * As such a [[AndThenProjection]] can be combined with a [[OrElseProjection]].
   *
   * For example:
   * {{{
   *   val projection1 : Projection = ...
   *   val projection2 : Projection = ...
   *   val projection3 : Projection = ...
   *
   *   val finalProjection = (projection1 andThen projection2) orElse projection3
   *
   *   finalProjection.onEvent(SomeEvent("abc"))
   *   // if SomeEvent("abc") is not defined for projection1 nor for projection2, projection3 will be applied
   * }}}
   *
   */
  private[funcqrs] class AndThenProjection(firstProj: Projection, secondProj: Projection) extends ComposedProjection(firstProj, secondProj) with Projection {

    import scala.concurrent.ExecutionContext.Implicits.global

    val projections = Seq(firstProj, secondProj)

    def handleEvent: HandleEvent = {
      // note that we only broadcast if at least one of the underlying
      // projections is defined for the incoming event
      // as such we make it possible to compose using orElse
      case domainEvent if composedHandleEvent.isDefinedAt(domainEvent) =>
        // send event to all projections
        firstProj.onEvent(domainEvent).flatMap { _ =>
          secondProj.onEvent(domainEvent)
        }
    }
  }

  /**
   * A [[Projection]] composed of two other Projections.
   *
   * Its `receiveEvent` is defined in terms of the `receiveEvent` method form the first Projection
   * with fallback to the `receiveEvent` method of the second Projection.
   *
   * As such the second Projection is only applied if the first Projection is not defined
   * for the given incoming [[DomainEvent]]
   *
   */
  private[funcqrs] class OrElseProjection(firstProj: Projection, secondProj: Projection) extends ComposedProjection(firstProj, secondProj) with Projection {
    def handleEvent = composedHandleEvent
  }

  private[funcqrs] class ComposedProjection(firstProj: Projection, secondProj: Projection) {
    // compose underlying receiveEvents PartialFunction in order
    // to decide if this Projection is defined for given incoming DomainEvent
    private[funcqrs] def composedHandleEvent = firstProj.handleEvent orElse secondProj.handleEvent
  }

}