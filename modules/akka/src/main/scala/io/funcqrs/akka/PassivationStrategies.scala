package io.funcqrs.akka

import akka.actor.ActorRef
import com.typesafe.config.{ ConfigFactory, Config }
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Defines a passivation strategy for aggregate instances.
 */
sealed trait PassivationStrategy

object PassivationStrategy extends LazyLogging {
  val defaultConfigPath = "funcqrs.akka.passivation-strategy"

  def apply(configPathOpt: Option[String]) = {
    val configPath = configPathOpt.getOrElse(defaultConfigPath)

    val _config = ConfigFactory.load()
    val config: Config = Try {
      _config.getConfig(configPath)
    }.getOrElse {
      logger.warn(
        s"""
           |#=============================================================================
           |# Could not load path {}.class from config.
           |# Are you sure {} is configured?
           |#
           |# Falling back to default config path
           |#=============================================================================
          """.stripMargin, configPath, configPath
      )
      _config.getConfig(defaultConfigPath)
    }

    val configuredClassName = config.getString("class")

    Try {
      //laad de class
      Thread.currentThread()
        .getContextClassLoader
        .loadClass(configuredClassName)
        .getDeclaredConstructor(classOf[Config])
        .newInstance(config)
        .asInstanceOf[PassivationStrategy]
    }.recover {
      case e: ClassNotFoundException =>

        logger.warn(
          s"""
               |#=============================================================================
               |# Could not load class configured for {}.class.
               |# Are you sure {} is correct and in your classpath?
               |#
               |# Falling back to default passivation strategy
               |#=============================================================================
          """.stripMargin, configPath, configuredClassName
        )

        new MaxChildrenPassivationStrategy(config)

      case _: InstantiationException | _: IllegalAccessException =>

        logger.warn(
          """"
              |#=======================================================================================
              |# Could not instantiate the passivation strategy.
              |# Are you sure {} has a constructor for Config and is a subclass of PassivationStrategy?
              |#
              |# Falling back to default passivation strategy
              |#====================================================================================
            """.stripMargin, configuredClassName
        )

        new MaxChildrenPassivationStrategy(config)

      case NonFatal(exp) =>
        //class niet gevonden, laad de default
        logger.error("Unknown error while loading passivation strategy class. Falling back to default passivation strategy.", exp)
        new MaxChildrenPassivationStrategy(config)
    }.getOrElse(new MaxChildrenPassivationStrategy(config))
  }
}

/**
 * Defines a passivation strategy that will kill child actors when creating a new child
 * will push us over a threshold
 */
class MaxChildrenPassivationStrategy(config: Config) extends PassivationStrategy {

  val max = {
    Try(config.getInt("max-children.max")).getOrElse(40)
  }

  val killAtOnce = {
    Try(config.getInt("max-children.kill-at-once")).getOrElse(20)
  }
  /**
   * Return all the children that may be killed.
   *
   * @param candidates all the children for a given AggregateManager
   * @return
   */
  def determineChildrenToKill(candidates: Iterable[ActorRef]): Iterable[ActorRef] = {
    if (candidates.size > max) {
      candidates.take(killAtOnce)
    } else {
      Nil
    }
  }

  override def toString = s"${this.getClass.getSimpleName}(max=$max,killAtOnce=$killAtOnce)"
}

/**
 * Defines a passivation strategy that will kill child actors when they have been idle for too long
 */
class InactivityTimeoutPassivationStrategy(config: Config) extends PassivationStrategy {

  /**
   * Determines how long they can idle in memory
   *
   * @return
   */

  val inactivityTimeout: Duration = {
    Try(Duration(config.getLong("inactivity-timeout"), SECONDS)).getOrElse(1.hours)
  }

  override def toString = s"${this.getClass.getSimpleName}(inactivityTimeout=$inactivityTimeout)"
}