package io.funcqrs.akka

import io.funcqrs.AggregateLike
import io.funcqrs.config.AggregateConfigLike

object Implicits {
  implicit class PimpedAggregateConfig[A <: AggregateLike](value: AggregateConfigLike[A]) {
    def withPassivationConfigPath(path: String): AkkaAggregateConfig[A] =
      AkkaAggregateConfig(value.name, Option(path), value.behavior)
  }
}
