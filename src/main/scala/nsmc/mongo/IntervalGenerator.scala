package nsmc.mongo

import com.mongodb.casbah.Imports._
import com.mongodb.casbah.MongoClient

import com.mongodb.{DBObject, BasicDBObject}
import org.bson.types.{MaxKey, MinKey}

import scala.collection.mutable

import scala.collection.JavaConversions._

class IntervalGenerator(client: MongoClient, dbName: String, collectionName: String) {

  private val MIN_KEY_TYPE = new MinKey()
  private val MAX_KEY_TYPE = new MaxKey()

  private def makeInterval(lowerBound: MongoDBObject, upperBound: MongoDBObject, destination: Destination): MongoInterval = {
    val splitMin:DBObject  = new BasicDBObject()
    if (lowerBound != null) {
      lowerBound.entrySet().iterator().foreach(entry => {
        val key = entry.getKey()
        val value = entry.getValue()
        if (!value.equals(MIN_KEY_TYPE))
        {
          splitMin.put(key, value);
        }
      })
    }
    val splitMax:DBObject  = new BasicDBObject()
    if (upperBound != null) {
      upperBound.entrySet().iterator().foreach(entry => {
        val key = entry.getKey();
        val value = entry.getValue();

        if (!value.equals(MAX_KEY_TYPE)) {
          splitMax.put(key, value);
        }
      })
    }
    new MongoInterval(splitMin, splitMax, destination)
  }

  def generateFromDB() : Seq[MongoInterval] = {
    val intervals = new mutable.ListBuffer[MongoInterval]()

    intervals
  }

  // TODO: only go directly to shards if allowed
  // TODO: use Mongo to generate intervals if not sharded

  // get the intervals for a sharded collection
  def generate() : Seq[MongoInterval] = {

    val intervals = new mutable.ListBuffer[MongoInterval]()

    val coll = client.getDB(dbName)(collectionName)
    if (!coll.getStats.getBoolean("ok", false)) {
      // TODO: throw something
    }
    if (!coll.getStats.getBoolean("sharded", false)) {
      // all in one partition
      val hostPort = client.getConnectPoint
      val interval = makeInterval(null, null, Destination(hostPort))
    } else {
      // use chunks to create intervals
      val configDb = client.getDB("config")

      // create a shard to table in order to know where to connect for chunk data
      val shards = new mutable.HashMap[String, String]()
      val shardsCol = configDb("shards")
      shardsCol.foreach(dbo => shards.+=((dbo.get("_id").asInstanceOf[String], dbo.get("host").asInstanceOf[String])))
      shards.foreach(kv => println(kv._1))

      // get the chunks for this collection
      val chunksCol = configDb("chunks")
      chunksCol.foreach(dbo => {
        if (dbo.get("ns").asInstanceOf[String].equals(dbName + ":" + collectionName)) {
          val shardName = dbo.get("shard").asInstanceOf[String]

          shards.get(shardName) match {
            case None => {}
            case Some(hostPort) => {
              val interval =
                makeInterval(dbo.get("min").asInstanceOf[MongoDBObject],
                  dbo.get("max").asInstanceOf[MongoDBObject],
                  Destination(hostPort))
              intervals.append(interval)
            }
          }
        }
      })
    }
    intervals
  }

  // get intervals for an un-sharded collection as suggested by MongoDB
  def generateSyntheticIntervals(maxChunkSize: Int) : Seq[MongoInterval] = {
    val keyPattern = "key" -> 1
    val splitCommand =
      MongoDBObject("splitVector" -> (dbName + "." + collectionName)) ++
        ("keyPattern" -> keyPattern) ++ ("maxChunkSize" -> maxChunkSize)
    val result = client.getDB(dbName).command(splitCommand)
    if (!result.ok()) {
      // log and throw something
    }
    val maybeSplitKeys = result.getAs[MongoDBList]("splitKeys")
    val hostPort = client.getConnectPoint

    val destination = Destination(hostPort)

    var previous:MongoDBObject = null
    val intervals = new mutable.ListBuffer[MongoInterval]()
    maybeSplitKeys match {
      case None => // log and throw something
      case Some(splitKeys) =>
        splitKeys.foreach(o => {
          val kv = o.asInstanceOf[MongoDBObject]
          val interval = makeInterval(previous, kv, destination)
          intervals += interval
          previous = kv
        })
      intervals += makeInterval(previous, null, destination)
    }

    intervals
  }
}