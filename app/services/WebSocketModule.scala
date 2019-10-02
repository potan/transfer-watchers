package services

import com.google.inject.AbstractModule
import com.google.inject.name.Names
import javax.inject._
import play.api.inject.ApplicationLifecycle
import controllers.BlockController
import akka.stream.scaladsl.Flow
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import akka.actor.ActorSystem
import akka.{Done, NotUsed}
import akka.http.scaladsl.Http
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsError
import play.api.Configuration

/**
 * A barebones adapter for the WS API.
 * Its basic functionality is to listen for:
 * - blocks added
 * - blocks finalised
 * and dispatch commands to the logic.
 * 
 * This implementation is very bare bones and error prone:
 * - there in no buffering of events above what Akka streams does OOTB.
 * - duplicates will not be ignored
 * - no synchronisation means that added blocks may be processed many times
 * - probably more, please don't use this in production.
 */

class WebSocketModule extends AbstractModule {
  override def configure() = {
    println("registering RChain /events listener")
    bind(classOf[WebSocketService]).asEagerSingleton()
  }
}

sealed trait RChainEvent {}

final case class BlockCreated(
    `block-hash`: String,
    `parent-hashes`: List[String],
    `justification-hashes`: List[(String, String)],
    `deploy-ids`: List[String],
    creator: String,
    `seq-num`: Int)
    extends RChainEvent

final case class BlockAdded(
    `block-hash`: String,
    `parent-hashes`: List[String],
    `justification-hashes`: List[(String, String)],
    `deploy-ids`: List[String],
    creator: String,
    `seq-num`: Int)
    extends RChainEvent

final case class BlockFinalised(`block-hash`: String) extends RChainEvent
final case class UnknownEvent(`event`: String) extends RChainEvent

object RChainEvent {
  implicit val blockAddedReads = Json.reads[BlockAdded]
  implicit val blockCreatedReads = Json.reads[BlockCreated]
  implicit val blockFinalizedReads = Json.reads[BlockFinalised]
  val unknownEvent = Json.reads[UnknownEvent]
  implicit val rChainEventReads: Reads[RChainEvent] =
    (__ \ "event").read[String].flatMap {
      case "block-created" =>
        (__ \ "payload").read[BlockCreated].map(_.asInstanceOf[RChainEvent])
      case "block-added" =>
        (__ \ "payload").read[BlockAdded].map(_.asInstanceOf[RChainEvent])
      case "block-finalised" =>
        (__ \ "payload").read[BlockFinalised].map(_.asInstanceOf[RChainEvent])
      case _ => unknownEvent.map(_.asInstanceOf[RChainEvent])
    }

}

@Singleton
class WebSocketService @Inject()(
    lifecycle: ApplicationLifecycle,
    blockController: BlockController,
    config: Configuration
  )(implicit ec: ExecutionContext) {
  lifecycle.addStopHook { () =>
    this.shutdown()
  }

  def setup() = {
    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    val dispatchRChainEvents: Sink[Message, Future[Done]] =
      Sink.foreach {
        case message: TextMessage.Strict =>
          val js = Json.parse(message.text).validate[RChainEvent]
          js match {
            case JsSuccess(value, path) =>
              value match {
                case ba: BlockAdded => blockController.processBlockAdded(ba)
                case bf: BlockFinalised => 
                  blockController.processFinalized(bf.`block-hash`)
                case _              => println("ignored event!")
              }
            case JsError(errors) => println("error! " + errors)
          }
        case a => println("other " + a)
      }
    val url = s"ws://${config.get[String]("rchain.url")}/ws/events"

    val KeepAliveMsg = TextMessage.apply("KeepAlive")
    val src = Source.tick[Message](2.seconds, 5.seconds, KeepAliveMsg)

    val webSocketFlow = Http().webSocketClientFlow(WebSocketRequest(url))
    val ((ws, upgradeResponse), closed) =
      src.viaMat(webSocketFlow)(Keep.both).toMat(dispatchRChainEvents)(Keep.both).run()

    val connected = upgradeResponse.map { upgrade =>
      if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
        println("connected...")
      } else {
        throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
      }
    }

    connected.onComplete(c => println("connection complete " + c))
    closed.foreach(msg => {
      println("closed - " + msg)
    })
  }
  setup()

  def shutdown(): Future[Unit] =
    Future.successful(())
}
