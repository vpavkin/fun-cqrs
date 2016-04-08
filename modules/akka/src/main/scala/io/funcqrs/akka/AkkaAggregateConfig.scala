package io.funcqrs
package akka

import behavior.Behavior
import config.AggregateConfigLike

case class AkkaAggregateConfig[A <: AggregateLike](
    name: Option[String],
    passivationConfigPath: Option[String],
    behavior: (A#Id) => Behavior[A]
) extends AggregateConfigLike[A] {

  def withName(name: String): AkkaAggregateConfig[A] =
    this.copy(name = Option(name))

  def withPassivationConfigPath(path: String): AkkaAggregateConfig[A] =
    this.copy(passivationConfigPath = Option(path))

}