package com.redislabs.provider.redis.streaming

import java.util.AbstractMap.SimpleEntry

import com.redislabs.provider.redis.{ReadWriteConfig, RedisConfig}
import com.redislabs.provider.redis.util.PipelineUtils.foreachWithPipeline
import com.redislabs.provider.redis.util.{Logging, PipelineUtils}
import org.apache.curator.utils.ThreadUtils
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.receiver.Receiver
import redis.clients.jedis.{EntryID, Jedis, StreamEntry}

import scala.collection.JavaConversions._

/**
  * Receives messages from Redis Stream
  *
  * TODO: max rate controller?
  */
class RedisStreamReceiver(consumersConfig: Seq[ConsumerConfig],
                          redisConfig: RedisConfig,
                          readWriteConfig: ReadWriteConfig,
                          storageLevel: StorageLevel)
  extends Receiver[StreamItem](storageLevel) with Logging {

  override def onStart(): Unit = {
    logInfo("Starting Redis Stream Receiver")
    val executorPool = ThreadUtils.newFixedThreadPool(consumersConfig.size, "RedisStreamMessageHandler")
    try {
      // start consumers in separate threads
      for (c <- consumersConfig) {
        executorPool.submit(new MessageHandler(c, redisConfig, readWriteConfig))
      }
    } finally {
      // terminate threads after the work is done
      executorPool.shutdown()
    }
  }

  override def onStop(): Unit = {
  }

  private class MessageHandler(conf: ConsumerConfig,
                               redisConfig: RedisConfig,
                               implicit val readWriteConfig: ReadWriteConfig) extends Runnable {

    val jedis: Jedis = redisConfig.connectionForKey(conf.streamKey)

    override def run(): Unit = {
      logInfo(s"Starting MessageHandler $conf")
      try {
        createConsumerGroupIfNotExist()
        receiveUnacknowledged()
        receiveNewMessages()
      } catch {
        case e: Exception =>
          restart("Error handling message. Restarting.", e)
      }
    }

    def createConsumerGroupIfNotExist(): Unit = {
      try {
        val entryId = conf.offset match {
          case Earliest => new EntryID(0, 0)
          case Latest => EntryID.LAST_ENTRY
          case IdOffset(v1, v2) => new EntryID(v1, v2)
        }
        jedis.xgroupCreate(conf.streamKey, conf.groupName, entryId, true)
      } catch {
        case e: Exception =>
          if (!e.getMessage.contains("already exists")) throw e
      }
    }

    def receiveUnacknowledged(): Unit = {
      logInfo(s"Starting receiving unacknowledged messages for key ${conf.streamKey}")
      var continue = true
      val unackId = new SimpleEntry(conf.streamKey, new EntryID(0, 0))

      while (!isStopped && continue) {
        val response = jedis.xreadGroup(
          conf.groupName,
          conf.consumerName,
          conf.batchSize,
          conf.block,
          false,
          unackId)

        val unackMessagesMap = response.map(e => (e.getKey, e.getValue)).toMap
        val entries = unackMessagesMap(conf.streamKey)
        if (entries.isEmpty) {
          continue = false
        }

        val streamItems = entriesToItems(conf.streamKey, entries)
        // call store(multiple-records) to reliably store in Spark memory
        store(streamItems.iterator)
        // ack redis
        foreachWithPipeline(jedis, entries) { (pipeline, entry) =>
          logInfo("unack" + entry)   // TODO
          pipeline.xack(conf.streamKey, conf.groupName, entry.getID)
        }
      }
    }

    def receiveNewMessages(): Unit = {
      logInfo(s"Starting receiving new messages for key ${conf.streamKey}")
      val newMessId = new SimpleEntry(conf.streamKey, EntryID.UNRECEIVED_ENTRY)

      while (!isStopped) {
        val response = jedis.xreadGroup(
          conf.groupName,
          conf.consumerName,
          conf.batchSize,
          conf.block,
          false,
          newMessId)

        for (streamMessages <- response) {
          val key = streamMessages.getKey
          val entries = streamMessages.getValue
          val streamItems = entriesToItems(key, entries)
          // call store(multiple-records) to reliably store in Spark memory
          store(streamItems.iterator)
          // ack redis
          foreachWithPipeline(jedis, entries) { (pipeline, entry) =>
            pipeline.xack(key, conf.groupName, entry.getID)
          }
        }
      }
    }

    def entriesToItems(key: String, entries: Seq[StreamEntry]): Seq[StreamItem] = {
      entries.map { e =>
        val itemId = ItemId(e.getID.getTime, e.getID.getSequence)
        StreamItem(key, itemId, e.getFields.toMap)
      }
    }
  }

}

/**
  *
  *
  * @param streamKey    redis stream key
  * @param groupName    consumer group name
  * @param consumerName consumer name
  * @param offset       stream offset
  * @param batchSize    maximum number of pulled items in a read API call
  * @param block        time in milliseconds to wait for data in a blocking read API call
  */
case class ConsumerConfig(streamKey: String,
                          groupName: String,
                          consumerName: String,
                          offset: Offset = Latest,
                          batchSize: Int = 100,
                          block: Long = 500)

/**
  * Represents an offset in the stream
  */
sealed trait Offset

/**
  * Latest offset, known as a '$' special id
  */
case object Latest extends Offset

/**
  * Earliest offset, '0-0' id
  */
case object Earliest extends Offset

/**
  * Specific id in the form of 'v1-v2'
  *
  * @param v1 first token of the id
  * @param v2 second token of the id
  */
case class IdOffset(v1: Long, v2: Long) extends Offset

/**
  * Item id in the form of 'v1-v2'
  *
  * @param v1 first token of the id
  * @param v2 second token of the id
  */
case class ItemId(v1: Long, v2: Long)

/**
  * Represent an item in the stream
  *
  * @param streamKey stream key
  * @param id        item(entry) id
  * @param fields    key/value map of item fields
  */
case class StreamItem(streamKey: String, id: ItemId, fields: Map[String, String])

