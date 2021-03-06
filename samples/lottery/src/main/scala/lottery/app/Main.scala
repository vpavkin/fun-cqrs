package lottery.app

import akka.actor.ActorSystem
import akka.util.Timeout
import io.funcqrs.akka.EventsSourceProvider
import io.funcqrs.akka.backend.AkkaBackend
import io.funcqrs.backend.{ Query, QueryByTag }
import io.funcqrs.config.api._
import lottery.domain.model.LotteryProtocol._
import lottery.domain.model.{ Lottery, LotteryId }
import lottery.domain.service.{ LevelDbTaggedEventsSource, LotteryViewProjection, LotteryViewRepo }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.{ Failure, Success }

object Main extends App {

  // tag::akka-backend[]
  val backend = new AkkaBackend { // #<1>
    val actorSystem: ActorSystem = ActorSystem("FunCQRS") // #<2>
    def sourceProvider(query: Query): EventsSourceProvider = { // #<3>
      query match {
        case QueryByTag(tag) => new LevelDbTaggedEventsSource(tag)
      }
    }
  }
  // end::akka-backend[]
  // tag::lottery-actor[]
  backend
    .configure { // aggregate config - write model
      aggregate[Lottery](Lottery.behavior) // #<4>
    }
  // end::lottery-actor[]

  // tag::lottery-projection[]
  val lotteryViewRepo = new LotteryViewRepo
  backend.configure { // projection config - read model
    projection(
      query = QueryByTag(Lottery.tag), // #<1>
      projection = new LotteryViewProjection(lotteryViewRepo), // #<2>
      name = "LotteryViewProjection" // #<3>
    ).withBackendOffsetPersistence() // #<4>
  }
  // end::lottery-projection[]

  // tag::lottery-run[]
  implicit val timeout = Timeout(3.seconds)

  val id = LotteryId.generate()

  val lotteryRef = backend.aggregateRef[Lottery](id) //#<1>

  val result =
    for {
      // create a lottery
      createEvts <- lotteryRef ? CreateLottery("Demo") // #<2>

      // add participants #<3>
      johnEvts <- lotteryRef ? AddParticipant("John")
      paulEvts <- lotteryRef ? AddParticipant("Paul")
      ringoEvts <- lotteryRef ? AddParticipant("Ringo")
      georgeEvts <- lotteryRef ? AddParticipant("George")

      // run the lottery
      runEvts <- lotteryRef ? Run // #<4>

    } yield {
      // concatenate all events together
      createEvts ++ johnEvts ++ paulEvts ++ ringoEvts ++ georgeEvts ++ runEvts // #<5>
    }
  // end::lottery-run[]

  waitAndPrint(result)
  Thread.sleep(5000)

  // ---------------------------------------------
  // fetch read model
  val viewResult = lotteryViewRepo.find(id)
  waitAndPrint(viewResult)

  Thread.sleep(1000)
  backend.actorSystem.terminate()

  def waitAndPrint[T](resultFut: Future[T]) = {
    Await.ready(resultFut, 3.seconds)
    resultFut.onComplete {
      case Success(res) => println(s" => result: $res")
      case Failure(ex) => println(s"FAILED: ${ex.getMessage}")
    }
  }
}