/*
 * Copyright 2016 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.metrix

import com.codahale.metrics.{Gauge, MetricRegistry}
import uk.gov.hmrc.lock.ExclusiveTimePeriodLock
import uk.gov.hmrc.metrix.domain.{MetricCount, MetricRepository, MetricSource}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class MetricCache {

  private val cache = mutable.Map[String, Int]()

  def refreshWith(allMetrics: List[MetricCount]) = {
    allMetrics.foreach(m => cache.put(m.name, m.count))
    val asMap: Map[String, Int] = allMetrics.map(m => m.name -> m.count).toMap
    cache.keys.foreach(key => if (!asMap.contains(key)) cache.remove(key))
  }

  def valueOf(name: String): Int = cache.getOrElse(name, 0)
}

final case class CachedMetricGauge(name: String, metrics: MetricCache) extends Gauge[Int] {
  override def getValue: Int = metrics.valueOf(name)
}

class MetricOrchestrator(metricSources: List[MetricSource],
                         lock: ExclusiveTimePeriodLock,
                         metricRepository: MetricRepository,
                         metricRegistry: MetricRegistry) {

  val metricCache = new MetricCache()

  private def updateMetricRepository()(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    Future.traverse(metricSources) { source => source.metrics }
      .map(list => {
        val currentMetrics: Map[String, Int] = list reduce (_ ++ _)
        currentMetrics.foreach {
          case (name, value) => metricRepository.persist(MetricCount(name, value))
        }
        currentMetrics
      })
  }

  def refreshAll()(implicit ec: ExecutionContext): Future[Map[String, Int]] = {

    for {
      updated <- lock.tryToAcquireOrRenewLock {
        updateMetricRepository
      }
      allMetrics <- metricRepository.findAll()
    } yield {

      metricCache.refreshWith(allMetrics)

      allMetrics
        .foreach(metric => if (!metricRegistry.getGauges.containsKey(metric.name))
          metricRegistry.register(metric.name, CachedMetricGauge(metric.name, metricCache)))

      allMetrics.map(m => m.name -> m.count).toMap

    }
  }
}
