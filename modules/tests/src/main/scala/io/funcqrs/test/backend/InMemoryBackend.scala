package io.funcqrs.test.backend

import io.funcqrs._
import io.funcqrs.backend.{ QuerySelectAll, Backend, QueryByTag, QueryByTags }
import io.funcqrs.behavior.{ Behavior, State, Uninitialized, Initialized }
import io.funcqrs.config.{ AggregateConfigLike, ProjectionConfig }
import io.funcqrs.interpreters.Monads._
import io.funcqrs.interpreters.{ Identity, IdentityInterpreter }
import rx.lang.scala.Subject
import rx.lang.scala.subjects.PublishSubject

import scala.collection.concurrent.TrieMap
import scala.collection.{ concurrent, immutable }
import scala.concurrent.Await
import scala.reflect.ClassTag
import scala.concurrent.duration._

class InMemoryBackend extends Backend[Identity] {

  private var aggregateConfigs: concurrent.Map[ClassTag[_], AggregateConfigLike[_]] = concurrent.TrieMap()
  private var aggregates: concurrent.Map[AggregateId, IdentityAggregateRef[_]] = TrieMap()

  private val eventStream: Subject[DomainEvent] = PublishSubject()

  private val stream: Stream[DomainEvent] = Stream()

  def aggregateRef[A <: AggregateLike: ClassTag](id: A#Id): InMemoryAggregateRef[A] = {

    aggregates.getOrElseUpdate(
      id,
      { // build new aggregateRef if not existent
        val config = aggregateConfigs(ClassTagImplicits[A]).asInstanceOf[AggregateConfigLike[A]]
        val behavior = config.behavior(id)
        new InMemoryAggregateRef(id, behavior)
      }
    ).asInstanceOf[InMemoryAggregateRef[A]]
  }

  def configure[A <: AggregateLike: ClassTag](config: AggregateConfigLike[A]): Backend[Identity] = {
    aggregateConfigs += (ClassTagImplicits[A] -> config)
    this
  }

  def configure(config: ProjectionConfig): Backend[Identity] = {

    // does the event match the query criteria?
    def matchQuery(evt: DomainEvent with MetadataFacet[_]): Boolean = {

      config.query match {
        case QueryByTag(tag) => evt.tags.contains(tag)
        case QueryByTags(tags) => tags.subsetOf(evt.tags)
        case QuerySelectAll => true
      }
    }

    //noinspection MatchToPartialFunction
    eventStream.subscribe { evt: DomainEvent =>

      evt match {
        case evt: DomainEvent with MetadataFacet[_] if matchQuery(evt) =>
          // TODO: projections should be interpreted as well to avoid this
          Await.ready(config.projection.onEvent(evt), 10.seconds)
          ()
        case anyEvent => // do nothing, don't send to projection
      }
    }

    this
  }

  private def publishEvents(evts: immutable.Seq[DomainEvent]): Unit = {
    evts foreach publishEvent
  }

  private def publishEvent(evt: DomainEvent): Unit = {
    eventStream.onNext(evt)
  }

  class InMemoryAggregateRef[A <: AggregateLike](id: A#Id, behavior: Behavior[A]) extends IdentityAggregateRef[A] {

    private var aggregateState: State[A] = Uninitialized(id)

    val interpreter = IdentityInterpreter(behavior)

    def ask(cmd: Command): Identity[Events] =
      handle(aggregateState, cmd)

    def tell(cmd: Command): Unit = {
      ask(cmd)
      () // omit events
    }

    private def handle(state: State[Aggregate], cmd: Command): interpreter.Events = {
      val (events, updatedAgg) = interpreter.applyCommand(cmd, state)
      aggregateState = updatedAgg
      publishEvents(events)
      events
    }

    def state(): Identity[A] =
      aggregateState match {
        case Initialized(aggregate) => aggregate
        case Uninitialized(_) => sys.error("Aggregate is not initialized")
      }

    def exists(): Identity[Boolean] = aggregateState.isInitialized

  }
}
