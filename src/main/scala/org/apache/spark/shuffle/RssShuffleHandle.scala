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

package org.apache.spark.shuffle

import com.uber.rss.common.ServerList
import org.apache.spark.ShuffleDependency

private[spark] class RssShuffleHandle[K, V, C](
    shuffleId: Int,
    val appId: String,
    val appAttempt: String,
    val numMaps: Int,
    val user: String,
    val queue: String,
    val dependency: ShuffleDependency[K, V, C],
    val rssServers: Array[RssShuffleServerHandle],
    val partitionFanout: Int = 1)
  extends ShuffleHandle(shuffleId) {

  def getServerList: ServerList = {
    new ServerList(rssServers.map(_.toServerDetail()))
  }

  override def toString: String = s"RssShuffleHandle (shuffleId $shuffleId, numMaps: $numMaps, rssServers: ${rssServers.length} servers), partitionFanout: $partitionFanout"
}