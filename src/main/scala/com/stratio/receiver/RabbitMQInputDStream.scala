/**
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.receiver

import java.util

import scala.util._
import scala.reflect.{classTag, ClassTag}

import com.rabbitmq.client._
import org.apache.spark.Logging
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.ReceiverInputDStream
import org.apache.spark.streaming.receiver.Receiver

private[receiver]
class RabbitMQInputDStream[T: ClassTag](
                            @transient ssc_ : StreamingContext,
                            rabbitMQQueueName: Option[String],
                            rabbitMQHost: String,
                            rabbitMQPort: Int,
                            exchangeName: Option[String],
                            routingKeys: Seq[String],
                            storageLevel: StorageLevel,
                            decoder: Array[Byte] => T
                            ) extends ReceiverInputDStream[T](ssc_) with Logging {

  override def getReceiver(): Receiver[T] = {
    val DefaultRabbitMQPort = 5672

    new RabbitMQReceiver[T](rabbitMQQueueName,
      Some(rabbitMQHost).getOrElse("localhost"),
      Some(rabbitMQPort).getOrElse(DefaultRabbitMQPort),
      exchangeName,
      routingKeys,
      storageLevel,
      decoder)
  }
}

private[receiver]
class RabbitMQReceiver[T: ClassTag](rabbitMQQueueName: Option[String],
                       rabbitMQHost: String,
                       rabbitMQPort: Int,
                       exchangeName: Option[String],
                       routingKeys: Seq[String],
                       storageLevel: StorageLevel,
                       decoder: Array[Byte] => T)
  extends Receiver[T](storageLevel) with Logging {

  val DirectExchangeType: String = "direct"

  def onStart() {
    implicit val akkaSystem = akka.actor.ActorSystem()
    getConnectionAndChannel match {
      case Success((connection: Connection, channel: Channel)) => receive(connection, channel)
      case Failure(f) => log.error("Could not connect"); restart("Could not connect", f)
    }
  }

  def onStop() {
    // There is nothing much to do as the thread calling receive()
    // is designed to stop by itself isStopped() returns false
  }

  /** Create a socket connection and receive data until receiver is stopped */
  private def receive(connection: Connection, channel: Channel) {

    val queueName = !routingKeys.isEmpty match {
      case true => {
        channel.exchangeDeclare(exchangeName.get, DirectExchangeType)
        val queueName = channel.queueDeclare().getQueue()

        for (routingKey: String <- routingKeys) {
          channel.queueBind(queueName, exchangeName.get, routingKey)
        }
        queueName
      }
      case false => {
        channel.queueDeclare(rabbitMQQueueName.get, true, false, false, new util.HashMap(0))
        rabbitMQQueueName.get
      }
    }

    log.info("RabbitMQ Input waiting for messages")
    val consumer: QueueingConsumer = new QueueingConsumer(channel)
    channel.basicConsume(queueName, false, consumer)

    while (!isStopped) {
      val delivery: QueueingConsumer.Delivery = consumer.nextDelivery
      log.info("delivering...")
      store(decoder(delivery.getBody))
      channel.basicAck(delivery.getEnvelope.getDeliveryTag, false)
    }

    log.info("it has been stopped")
    channel.close
    connection.close
    restart("Trying to connect again")
  }

  private def getConnectionAndChannel: Try[(Connection, Channel)] = {
    for {
      connection: Connection <- Try(getConnectionFactory.newConnection())
      channel: Channel <- Try(connection.createChannel)
    } yield {
      (connection, channel)
    }
  }

  private def getConnectionFactory: ConnectionFactory = {
    val factory: ConnectionFactory = new ConnectionFactory
    factory.setHost(rabbitMQHost)
    factory.setPort(rabbitMQPort)
    factory
  }
}
