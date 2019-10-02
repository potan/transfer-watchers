package models

import javax.inject.{Inject, Singleton}
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile
import slick.lifted.Rep._
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.PostgresProfile
import slick.jdbc.PostgresProfile.api._
import controllers.ExtractedTransfer

case class Block(hash: String, seqNum: Int, finalized: Boolean)
case class Parent(hash: String, parent: String)
case class Finalized(hash: String)
case class Transfer(
    block: String,
    deploy: String,
    fromVault: String,
    toVault: String,
    fromName: String,
    toName: String,
    fromBalance: String,
    toBalance: String,
    amount: String)

@Singleton
class BlockRepository @Inject()(dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext) {

  val dbConfig = dbConfigProvider.get[JdbcProfile]

  import dbConfig._
  import profile.api._

  class BlockTable(tag: Tag) extends Table[Block](tag, "blocks") {

    def hash = column[String]("hash", O.PrimaryKey)

    def seqNum = column[Int]("seq_num")

    def finalized = column[Boolean]("finalized")

    def * = (hash, seqNum, finalized) <> (Block.tupled, Block.unapply)

  }

  private val blocks = TableQuery[BlockTable]

  /**
   * create blocks only 
   */
  def create(hash: String, seqNum: Int) = db.run {
    blocks.forceInsertQuery {
      val exists = (for (b <- blocks if b.hash === hash) yield b).exists
      val insert = (hash.bind, seqNum.bind, false) <> (Block.tupled, Block.unapply)
      for (b <- Query(insert) if !exists) yield b
    }
  }

  def list(): Future[Seq[Block]] = db.run {
    blocks.result
  }

  def getByHashes(hashes: Set[String]) = db.run {
    blocks.filter(r => r.hash inSet (hashes)).result
  }

  def finalize(hash: String) = db.run {
    val q = for { c <- blocks if c.hash === hash } yield c.finalized
    val updateAction = q.update(true)
    updateAction
  }
}

@Singleton
class ParentsRepository @Inject()(dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext) {

  val dbConfig = dbConfigProvider.get[PostgresProfile]

  import dbConfig._
  import profile.api._

  class ParentTable(tag: Tag) extends Table[Parent](tag, "parents") {

    def hash = column[String]("hash")

    def parent = column[String]("parent")

    def * = (hash, parent) <> (Parent.tupled, Parent.unapply)

  }

  private val parents = TableQuery[ParentTable]

  def create(hash: String, parent: String): Future[Parent] = db.run {
    (parents returning parents) += Parent(hash, parent)
  }

  def parents(hash: String): Future[Seq[Parent]] = db.run {
    parents.filter(_.hash === hash).result
  }

  def addParents(hash: String, pars: List[String]): Future[Unit] = db.run {
    val ps = pars.map(Parent(hash, _))
    parents.forceInsertAll(ps).map(_ => ())
  }
}

@Singleton
class TransfersRepostitory @Inject()(dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext) {
  val dbConfig = dbConfigProvider.get[PostgresProfile]

  import dbConfig._
  import profile.api._

  class TransfersTable(tag: Tag) extends Table[Transfer](tag, "transfers") {

    def block = column[String]("hash")
    def deploy = column[String]("deploy")
    def fromVault = column[String]("fromVault")
    def toVault = column[String]("toVault")
    def fromName = column[String]("fromName")
    def toName = column[String]("toName")
    def fromBalance = column[String]("fromBalance")
    def toBalance = column[String]("toBalance")
    def amount = column[String]("amount")

    def * =
      (block, deploy, fromVault, toVault, fromName, toName, fromBalance, toBalance, amount) <> (Transfer.tupled, Transfer.unapply)

  }

  private val transfers = TableQuery[TransfersTable]

  def insertTransfers(hash: String, ts: List[ExtractedTransfer]): Future[String] = db.run {
    val toWrite =
      ts.map(t => Transfer(hash, t.did, t.from, t.to, t.fromName, t.toName, t.fromBalance, t.toBalance, t.amount))
    transfers.forceInsertAll(toWrite).map(_ => hash)
  }

  def list(): Future[Seq[Transfer]] = db.run {
    transfers.result
  }

  def list(vault: String): Future[Seq[Transfer]] = db.run {
    transfers.filter(t => t.fromVault === vault || t.toVault === vault).result
  }
}
