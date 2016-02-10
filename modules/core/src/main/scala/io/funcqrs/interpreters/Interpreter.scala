package io.funcqrs.interpreters

import io.funcqrs.behavior.Behavior
import io.funcqrs.interpreters.Monads._
import io.funcqrs.{ DomainEvent, Projection, AggregateLike, AggregateAliases }

import scala.language.higherKinds

/**
 * Base Interpreter trait.
 *
 * Implementors must define which type F must be bound to.
 */
trait Interpreter[A <: AggregateLike, F[_]] extends AggregateAliases {

  type Aggregate = A

  val behavior: Behavior[A]

  def handleCommand(optionalAggregate: Option[Aggregate], cmd: Command): F[Events]

  def applyCommand(cmd: Command, optionalAggregate: Option[Aggregate])(implicit monadsOps: MonadOps[F]): F[(Events, Option[A])] = {
    monad(handleCommand(optionalAggregate, cmd)).map { evts: Events =>
      val optionalAgg = evts.foldLeft(optionalAggregate) {
        case (optionalAggregate, evt) =>
          Some(behavior.onEvent(optionalAggregate, evt))
      }
      (evts, optionalAgg)
    }
  }

}

object Interpreter {

  //  trait ReadModelOps[F[_]] {
  //
  //    implicit val monadsOps: MonadOps[F]
  //
  //    protected def sendToProjectionInternal(projection: Projection, events: Seq[DomainEvent]): F[Unit] = {
  //      events.foldLeft(monadsOps.pure(())) { (fut, evt) =>
  //        fut.flatMap { _ => projection.onEvent(evt) }
  //      }
  //    }
  //  }

  trait WriteModelOps[A <: AggregateLike, F[_]] {

    val interpreter: Interpreter[A, F]
    implicit val monadsOps: MonadOps[F]

    import interpreter.behavior._

    protected def sendCommandsInternal(
      events: Events,
      optionalAggregate: Option[Aggregate],
      cmds: Command*
    ): F[(Events, Option[Aggregate])] = {

      cmds.toList match {
        case head :: Nil =>
          monad(interpreter.applyCommand(head, optionalAggregate)).map {
            case (evts, aggOpt) =>
              // concat previous events with events from last command
              (events ++ evts, aggOpt)
          }
        case head :: tail =>
          monad(interpreter.applyCommand(head, optionalAggregate)).flatMap {
            case (evts, aggOpt) =>
              sendCommandsInternal(events ++ evts, aggOpt, tail: _*)
          }
        case Nil => monadsOps.pure((events, optionalAggregate))
      }

    }

  }
}