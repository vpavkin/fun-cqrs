package io.funcqrs.config

import io.funcqrs._
import io.funcqrs.behavior.Behavior

// ================================================================================
// support classes and traits for AggregateService creation!

trait AggregateConfigLike[A <: AggregateLike] {
  def name: Option[String]
  def behavior: (A#Id) => Behavior[A]
}

case class AggregateConfig[A <: AggregateLike](
    name: Option[String],
    behavior: (A#Id) => Behavior[A]
) extends AggregateConfigLike[A] {

  def withName(name: String): AggregateConfig[A] =
    this.copy(name = Option(name))

}
