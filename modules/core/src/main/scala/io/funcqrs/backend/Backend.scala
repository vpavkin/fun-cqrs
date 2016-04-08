package io.funcqrs.backend

import io.funcqrs.{ AggregateRef, AggregateLike }
import io.funcqrs.config.{ AggregateConfigLike, ProjectionConfig }

import scala.language.higherKinds
import scala.reflect.ClassTag

trait Backend[F[_]] {

  def configure[A <: AggregateLike: ClassTag](config: AggregateConfigLike[A]): Backend[F]

  def configure(config: ProjectionConfig): Backend[F]
}
