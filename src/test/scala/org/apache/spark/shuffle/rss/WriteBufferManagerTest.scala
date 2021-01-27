/*
 * Copyright (c) 2020 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.shuffle.rss

import java.util.Random

import com.esotericsoftware.kryo.io.Input
import org.apache.spark.SparkConf
import org.apache.spark.serializer.KryoSerializer
import org.testng.Assert
import org.testng.annotations.Test

class WriteBufferManagerTest {
  val serializer = new KryoSerializer(getConf)
  val maxBufferSize = 10000000

  @Test
  def singlePartition(): Unit = {
    var bufferSize = 2
    val spillSize = 100
    val record = (1, "123") // it is 7 bytes after serialization
    var bufferManager = new WriteBufferManager(serializer, bufferSize, maxBufferSize, spillSize)
    Assert.assertEquals(bufferManager.filledBytes, 0)

    var spilledData = bufferManager.clear().toList
    Assert.assertEquals(spilledData.size, 0)

    val partition1 = 1
    spilledData = bufferManager.addRecordImpl(partition1, record).toList
    Assert.assertEquals(spilledData.size, 1)
    Assert.assertEquals(bufferManager.filledBytes, 0)

    spilledData = bufferManager.clear().toList
    Assert.assertEquals(spilledData.size, 0)

    bufferSize = 20
    bufferManager = new WriteBufferManager(serializer, bufferSize, maxBufferSize, spillSize)
    spilledData = bufferManager.clear().toList
    Assert.assertEquals(spilledData.size, 0)

    spilledData = bufferManager.addRecordImpl(partition1, record).toList
    Assert.assertEquals(spilledData.size, 0)
    Assert.assertTrue(bufferManager.filledBytes > 0)

    spilledData = bufferManager.addRecordImpl(partition1, record).toList
    Assert.assertEquals(spilledData.size, 0)

    spilledData = bufferManager.addRecordImpl(partition1, record).toList
    Assert.assertEquals(spilledData.size, 1)

    Assert.assertEquals(bufferManager.filledBytes, 0)

    spilledData = bufferManager.addRecordImpl(partition1, record).toList
    Assert.assertEquals(spilledData.size, 0)
    Assert.assertTrue(bufferManager.filledBytes > 0)

    spilledData = bufferManager.addRecordImpl(partition1, record).toList
    Assert.assertEquals(spilledData.size, 0)

    spilledData = bufferManager.addRecordImpl(partition1, record).toList
    Assert.assertEquals(spilledData.size, 1)

    Assert.assertEquals(bufferManager.filledBytes, 0)

    spilledData = bufferManager.clear().toList
    Assert.assertEquals(spilledData.size, 0)
    Assert.assertEquals(bufferManager.filledBytes, 0)
  }

  @Test
  def multiPartitions(): Unit = {
    val bufferSize = 20
    val spillSize = 30
    val bufferManager = new WriteBufferManager(serializer, bufferSize, maxBufferSize, spillSize)
    var spilledData = bufferManager.clear().toList
    Assert.assertEquals(spilledData.size, 0)

    val partition1 = 1
    val partition2 = 2
    val record = (1, "123") // it is 7 bytes after serialization
    spilledData = bufferManager.addRecordImpl(partition1, record).toList
    Assert.assertEquals(spilledData.size, 0)
    Assert.assertTrue(bufferManager.filledBytes > 0)

    spilledData = bufferManager.addRecordImpl(partition2, record).toList
    Assert.assertEquals(spilledData.size, 0)

    spilledData = bufferManager.addRecordImpl(partition1, record).toList
    Assert.assertEquals(spilledData.size, 0)

    val filledBytes1 = bufferManager.filledBytes

    spilledData = bufferManager.addRecordImpl(partition1, record).toList
    Assert.assertEquals(spilledData.size, 1)
    Assert.assertTrue(bufferManager.filledBytes < filledBytes1)
    Assert.assertTrue(bufferManager.filledBytes > 0)
  }

  @Test
  def totalSizeExceedSpillSize(): Unit = {
    val bufferSize = 10
    val spillSize = 20
    val bufferManager = new WriteBufferManager(serializer, bufferSize, maxBufferSize, spillSize)

    val partition1 = 1
    val partition2 = 2
    val partition3 = 3
    val record = (1, "123") // it is 7 bytes after serialization
    var spilledData = bufferManager.addRecordImpl(partition1, record).toList
    Assert.assertEquals(spilledData.size, 0)
    Assert.assertTrue(bufferManager.filledBytes > 0)

    spilledData = bufferManager.addRecordImpl(partition2, record).toList
    Assert.assertEquals(spilledData.size, 0)

    spilledData = bufferManager.addRecordImpl(partition3, record).toList
    Assert.assertEquals(spilledData.size, 3)
    Assert.assertEquals(spilledData.map(_._1).sorted, Seq(partition1, partition2, partition3))
    Assert.assertEquals(bufferManager.filledBytes, 0)
  }

  @Test
  def randomTest(): Unit = {
    val bufferSize = 10
    val spillSize = 20
    val partitions = List(1, 2, 3, 4, 5)
    val records = List((1, "123"), (1, 2), (1, "123456789"), ("123456789", "123456789"))
    val recordSet = records.toSet

    val bufferManager = new WriteBufferManager(serializer, bufferSize, maxBufferSize, spillSize)

    val numRecords = 2000
    val random = new Random()
    var numDeserializedRecords = 0
    (0 until numRecords).foreach(_ => {
      val partition = partitions(random.nextInt(partitions.size))
      val record = records(random.nextInt(records.size))
      val spilledData = bufferManager.addRecordImpl(partition, record)

      Assert.assertTrue(bufferManager.filledBytes < spillSize)
      Assert.assertTrue(bufferManager.filledBytes >= 0)

      val deserializedRecords = spilledData.flatMap(t => deserializeData(t._2)).toList
      numDeserializedRecords += deserializedRecords.size

      deserializedRecords.foreach(t => {
        Assert.assertTrue(recordSet.contains(t))
      })
    })

    val remainingData = bufferManager.clear()
    Assert.assertEquals(bufferManager.filledBytes, 0)

    numDeserializedRecords += remainingData.flatMap(t => deserializeData(t._2)).size

    Assert.assertEquals(numDeserializedRecords, numRecords)
  }

  private def getConf = {
    new SparkConf().setAppName("testApp")
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
  }

  private def deserializeData(data: Array[Byte]): Seq[(Any, Any)] = {
    val input = new Input(data)
    val stream = serializer.newInstance().deserializeStream(input)
    stream.asKeyValueIterator.toList
  }
}
