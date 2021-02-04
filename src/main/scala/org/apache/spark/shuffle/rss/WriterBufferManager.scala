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

import com.esotericsoftware.kryo.io.Output
import com.uber.rss.exceptions.RssInvalidDataException
import org.apache.spark.internal.Logging
import org.apache.spark.serializer.{SerializationStream, Serializer, SerializerInstance}

import scala.collection.mutable
import scala.collection.mutable.Map

case class BufferManagerOptions(individualBufferSize: Int, individualBufferMax: Int, bufferSpillThreshold: Int)

case class WriterBufferManagerValue(serializeStream: SerializationStream, output: Output)

class WriteBufferManager[K, V, C](serializer: Serializer,
                               bufferSize: Int,
                               maxBufferSize: Int,
                               spillSize: Int) extends Logging {

  def this(serializer: Serializer, bufferOptions: BufferManagerOptions) {
    this(serializer, bufferOptions.individualBufferSize,
      bufferOptions.individualBufferMax, bufferOptions.bufferSpillThreshold)
  }

  private val map: Map[Int, WriterBufferManagerValue] = Map()

  private var totalBytes = 0

  def recordsWritten: Int = recordsWrittenCount

  def numberOfSpills: Int = spillCount

  def reductionFactor: Double = 0.0

  private var recordsWrittenCount: Int = 0

  private var spillCount: Int = 0

  def addRecord(partitionId: Int, record: Product2[K, V]): Seq[(Int, Array[Byte])] = {
    addRecordImpl(partitionId, record)
  }
  private val serializerInstance = serializer.newInstance()

  private[rss] def addRecordImpl(partitionId: Int, record: Product2[Any, Any]): Seq[(Int, Array[Byte])] = {
    val result = mutable.Buffer[(Int, Array[Byte])]()
    recordsWrittenCount += 1
    map.get(partitionId) match {
      case Some(v) =>
        val stream = v.serializeStream
        val oldSize = v.output.position()
        stream.writeKey(record._1)
        stream.writeValue(record._2)
        stream.flush()
        val newSize = v.output.position()
        if (newSize >= bufferSize) {
          spillCount += 1
          result.append((partitionId, v.output.toBytes))
          v.serializeStream.close()
          map.remove(partitionId)
          totalBytes -= oldSize
        } else {
          totalBytes += (newSize - oldSize)
        }
      case None =>
        val output = new Output(bufferSize, maxBufferSize)
        val stream = serializerInstance.serializeStream(output)
        stream.writeKey(record._1)
        stream.writeValue(record._2)
        stream.flush()
        val newSize = output.position()
        if (newSize >= bufferSize) {
          spillCount += 1
          result.append((partitionId, output.toBytes))
          stream.close()
        } else {
          map.put(partitionId, WriterBufferManagerValue(stream, output))
          totalBytes = totalBytes + newSize
        }
    }

    if (totalBytes >= spillSize) {
      spillCount += 1
      result.appendAll(map.map(t=>(t._1, t._2.output.toBytes)))
      map.foreach(t => t._2.serializeStream.close())
      map.clear()
      totalBytes = 0
    }

    result
  }

  def filledBytes: Int = {
    val sum = map.map(_._2.output.position()).sum
    if (sum != totalBytes) {
      throw new RssInvalidDataException(s"Inconsistent internal state, total bytes is $totalBytes, but should be $sum")
    }
    totalBytes
  }

  def clear(): Seq[(Int, Array[Byte])] = {
    val result = map.map(t=>(t._1, t._2.output.toBytes)).toSeq
    map.foreach(t => t._2.serializeStream.close())
    map.clear()
    totalBytes = 0
    if (!result.isEmpty) {
      spillCount += 1
    }
    result
  }

  def releaseMemory(memoryToHold: Long = 0L): Unit = {}
}
