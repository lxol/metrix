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
import play.api.Logger
import uk.gov.hmrc.lock.ExclusiveTimePeriodLock
import uk.gov.hmrc.metrix.domain.{MetricRepository, MetricSource, PersistedMetric}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class MetricCache {

  private val cache = mutable.Map[String, Int]()

  def refreshWith(allMetrics: List[PersistedMetric]) = {
    allMetrics.foreach(m => cache.put(m.name, m.count))
    val asMap: Map[String, Int] = allMetrics.map(m => m.name -> m.count).toMap
    cache.keys.foreach(key => if (!asMap.contains(key)) cache.remove(key))
  }

  def valueOf(name: String): Int = cache.getOrElse(name, 0)
}

final case class CachedMetricGauge(name: String, metrics: MetricCache) extends Gauge[Int] {
  override def getValue: Int = metrics.valueOf(name)
}

trait MetricOrchestrationResult {
  def andLogTheResult()
}

final case class MetricsUpdatedAndRefreshed(updatedMetrics: Map[String, Int],
                                            refreshedMetrics: Seq[PersistedMetric]) extends MetricOrchestrationResult {
  override def andLogTheResult(): Unit = {
    Logger.info(s"I have acquired the lock. Both update and refresh have been performed.")
    Logger.debug(
      s"""
         | The updated metrics coming from sources are: $updatedMetrics.
         | Metrics refreshed on the cache are: $refreshedMetrics
       """.stripMargin)
  }
}

final case class MetricsOnlyRefreshed(refreshedMetrics: List[PersistedMetric]) extends MetricOrchestrationResult {
  override def andLogTheResult(): Unit = {
    Logger.info(s"I have failed to acquire the lock. Therefore only refresh has been performed.")
    Logger.debug(
      s"""
         | Metrics refreshed on the cache are: $refreshedMetrics
       """.stripMargin)
  }
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
          case (name, value) => metricRepository.persist(PersistedMetric(name, value))
        }
        currentMetrics
      })
  }

  def attemptToUpdateAndRefreshMetrics()(implicit ec: ExecutionContext): Future[MetricOrchestrationResult] = {
    lock.tryToAcquireOrRenewLock {
      updateMetricRepository
    } flatMap { maybeUpdatedMetrics =>
      metricRepository.findAll() map { allMetrics =>
        metricCache.refreshWith(allMetrics)

        allMetrics
          .foreach(metric => if (!metricRegistry.getGauges.containsKey(metric.name))
            metricRegistry.register(metric.name, CachedMetricGauge(metric.name, metricCache)))

        maybeUpdatedMetrics match {
          case Some(updatedMetrics) => MetricsUpdatedAndRefreshed(updatedMetrics, allMetrics)
          case None => MetricsOnlyRefreshed(allMetrics)
        }
      }
    }
  }
}
