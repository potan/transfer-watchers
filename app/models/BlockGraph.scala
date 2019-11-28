package models

import javax.inject._
import play.api._

//import neotypes.GraphDatabase
import org.neo4j.driver.v1.{AuthTokens, GraphDatabase}
import neotypes.Driver
import neotypes.implicits._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
//import scala.concurrent.ExecutionContext.Implicits.global

import services.BlockAdded

object BlockGraph {
  def addBlockQuery(b: BlockAdded) : String = {
    val cb = s"""MERGE (b: Block {id:"${b.`block-hash`}", creater:"${b.creator}", seqNum:${b.`seq-num`}}) """
    b.`parent-hashes`.zipWithIndex.foldLeft(cb)((q,p) => q ++ s"""MERGE (b${p._2}: Block {id:"${p._1}"}) CREATE (b${p._2}) <-[:PARENT]- (b) """)
  }
}

@Singleton
class BlockGraph @Inject()(
    config: Configuration
  )(implicit ec: ExecutionContext) {
    val driver = GraphDatabase.driver/*[Future]*/(config.get[String]("graph.neo4j.url"), AuthTokens.basic(config.get[String]("graph.neo4j.user"), config.get[String]("graph.neo4j.password"))).asScala[Future]

    def addBlock(b: BlockAdded) = {
      val cb = BlockGraph.addBlockQuery(b)
      driver.writeSession(session => cb.query.single(session))
    }
}
