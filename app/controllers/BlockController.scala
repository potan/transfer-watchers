package controllers

import javax.inject._
import play.api._
import play.api.mvc._
import play.api.libs.ws._
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import models.BlockRepository
import scala.concurrent.{ExecutionContext, Future}
import models.ParentsRepository
import models.TransfersRepostitory
import BlockLogic._
import services.BlockAdded

/**
  * This controller creates an `Action` to handle HTTP requests to the
  * application's home page.
  */
@Singleton
class BlockController @Inject()(
    cc: ControllerComponents,
    blockRepository: BlockRepository,
    parentRepository: ParentsRepository,
    transfersRepository: TransfersRepostitory,
    ws: WSClient,
    config: Configuration
  )(implicit ec: ExecutionContext)
    extends AbstractController(cc) {

  implicit val blockAddedReads = Json.reads[BlockAdded]

  /**
    * Create an Action to render an HTML page.
    *
    * The configuration in the `routes` file means that this method
    * will be called when the application receives a `GET` request with
    * a path of `/`.
    */
  def add() = Action(parse.json).async { implicit request: Request[JsValue] =>
    val addedJs = request.body.validate[BlockAdded]
    addedJs match {
      case JsSuccess(value, path) =>
        processBlockAdded(value).map(_ => Ok)
      case JsError(errors) =>
        Future.successful(BadRequest)
    }
  }

  def trace(hash: String) = Action.async { implicit request =>
    processTrace(hash).map(_ => Ok)
  }

  def processBlockAdded(value: BlockAdded) =
    for {
      bl <- blockRepository.create(value.`block-hash`, value.`seq-num`)
      _ <- if (bl == 0) Future.successful(())
      else
        for { 
          _ <- parentRepository.addParents(value.`block-hash`, value.`parent-hashes`)
        } yield ()
    } yield ()

  def processTrace(hash: String) =
    for {
      btrO <- BlockLogic.fetchTrace(hash, ws, config.get[String]("rchain.url"))
      _ <- btrO match {
        case None => Future.successful("trace not found")
        case Some(bt) =>
          val tss = processTraceReport(bt)
          transfersRepository.insertTransfers(bt.hash, tss)
      } 
    } yield ()

  def finalized(hash: String) = Action.async { implicit request =>
    for {
      _ <- processFinalized(hash)
    } yield Ok
  }

  def processFinalized(hash: String) = {
    def finalizeBlock(h: String) =
      for {
        _ <- blockRepository.finalize(h)
      } yield ()
    def finalizeBlocks(hs: Seq[String]): Future[Unit] =
      for {
        _       <- Future.traverse(hs)(finalizeBlock)
        parents <- Future.traverse(hs)(h => parentRepository.parents(h)).map(_.flatten)
        _ <- if (parents.nonEmpty)
          finalizeBlocks(parents.map(_.parent))
        else
          Future.successful(())
      } yield ()
    finalizeBlocks(List(hash))
  }

  val transferR = """(?s).*\(\"([A-Za-z0-9]+)", \"([A-Za-z0-9]+)\", ([0-9]+)\).*""".r

  def subtractPattern(a: String) = s""")}!((x-1 - $a))"""
  def additionPattern(a: String) = s""")}!((x-1 + $a))"""

  def getVault(dt: DeployTrace, pattern: String): RhoProduce = {
    val subs = dt.events.filter {
      case RhoComm(consume, _) => {
        consume.continuation.contains(pattern)
      }
      case _ => false
    }
    val chs = subs.map(_.asInstanceOf[RhoComm].consume.channels)
    val ds = chs.map { ch =>
      dt.events.filter {
        case RhoProduce(channel, data) if (ch.contains(channel)) => true
        case _                                                   => false
      }
    }
    val n = ds.find(_.size == 1).get.head.asInstanceOf[RhoProduce]
    n
  }

  def processTraceReport(btr: BlockTracesReport) = {
    val hash = btr.hash
    val transfers = btr.traces.filter(dt => dt.source.startsWith(transferContractStart))
    val deploys = transfers.map(_.`deploy-hash`).map(Deploy(_, hash))
    val tss = transfers.map(dt => {
      val transferDefinition = dt.events.collectFirst { case c: RhoConsume if c.continuation.contains("""@{x7}!("transfer",""") => c }.get
      val transferR(f, t, a) = transferDefinition.continuation
      val fromV = getVault(dt, subtractPattern(a))
      val toV = getVault(dt, additionPattern(a))
      ExtractedTransfer(f, fromV.channel, fromV.data, t, toV.channel, toV.data, a, dt.`deploy-hash`)
    })
    tss
  }

  def listBlocks() = Action.async { implicit request: Request[AnyContent] =>
    for {
      blocks <- blockRepository.list()
    } yield Ok(views.html.blocks(blocks.toList))
  }

  def allTransfers() = Action.async { implicit request: Request[AnyContent] =>
    for {
      transfers <- transfersRepository.list
      bs        <- blockRepository.list()
      finalized = bs.map(r => (r.hash, r.finalized)).toMap.withDefaultValue(false)
    } yield Ok(views.html.transfers(transfers.toList, finalized))
  }

  def listTransfers(vault: String) = Action.async { implicit request: Request[AnyContent] =>
    for {
      transfers <- transfersRepository.list(vault)
      blockHashes = transfers.map(_.block)
      bs <- blockRepository.getByHashes(blockHashes.toSet)
      finalized = bs.map(r => (r.hash, r.finalized)).toMap.withDefaultValue(false)
    } yield Ok(views.html.transfers(transfers.toList, finalized))
  }
}

case class ExtractedTransfer(
    from: String,
    fromName: String,
    fromBalance: String,
    to: String,
    toName: String,
    toBalance: String,
    amount: String,
    did: String)
case class Deploy(did: String, blockHash: String)

object BlockLogic {

  sealed trait RhoEvent
  final case class RhoComm(consume: RhoConsume, produces: List[RhoProduce]) extends RhoEvent
  final case class RhoProduce(channel: String, data: String) extends RhoEvent
  final case class RhoConsume(channels: String, patterns: String, continuation: String) extends RhoEvent

  final case class DeployTrace(`deploy-hash`: String, source: String, events: List[RhoEvent])

  final case class BlockTracesReport(hash: String, traces: List[DeployTrace])

  implicit val rhoProduceReads = Json.reads[RhoProduce]
  implicit val rhoConsumeReads = Json.reads[RhoConsume]
  implicit val rhoCommReads = Json.reads[RhoComm]
  implicit val eventReads: Reads[RhoEvent] =
    (__ \ "type").read[String].flatMap {
      case "rho-comm"    => rhoCommReads.map(_.asInstanceOf[RhoEvent])
      case "rho-produce" => rhoProduceReads.map(_.asInstanceOf[RhoEvent])
      case "rho-consume" => rhoConsumeReads.map(_.asInstanceOf[RhoEvent])
    }
  implicit val traceReads = Json.reads[DeployTrace]
  implicit val reportReads = Json.reads[BlockTracesReport]

  def traceUrl(base: String) = s"http://$base/reporting/trace?blockHash="

  def fetchTrace(hash: String, ws: WSClient, baseUrl: String)(implicit ec: ExecutionContext) = {
    val url = traceUrl(baseUrl) + hash
    for {
      r <- ws.url(url).get()
      btr = r.json.validate[BlockTracesReport].asOpt
    } yield btr
  }

  val transferContractStart =
    """new
  rl(`rho:registry:lookup`), RevVaultCh,
  stdout(`rho:io:stdout`)
in {

  rl!(`rho:rchain:revVault`, *RevVaultCh) |
  for (@(_, RevVault) <- RevVaultCh) {

    stdout!(("3.transfer_funds.rho")) |

    // REPLACE THE REV ADDRESSES HERE vvv
    match ("""

    val transferContractEnd =
    """(from, to, amount) => {

        new vaultCh, revVaultkeyCh, deployerId(`rho:rchain:deployerId`) in {
          @RevVault!("findOrCreate", from, *vaultCh) |
          @RevVault!("deployerAuthKey", *deployerId, *revVaultkeyCh) |
          for (@(true, vault) <- vaultCh; key <- revVaultkeyCh) {

            stdout!(("Beginning transfer of ", amount, "REV from", from, "to", to)) |

            new resultCh in {
              @vault!("transfer", to, amount, *key, *resultCh) |
              for (@result <- resultCh) {

                stdout!(("Finished transfer of ", amount, "REV to", to, "result was:", result))
              }
            }
          }
        }
      }
    }
  }

}"""
}
