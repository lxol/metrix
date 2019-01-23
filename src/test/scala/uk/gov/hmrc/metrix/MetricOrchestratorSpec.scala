/*
 * Copyright 2019 HM Revenue & Customs
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

import com.codahale.metrics.{Metric, MetricFilter, MetricRegistry}
import org.joda.time.Duration
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.Inside._
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, LoneElement}
import uk.gov.hmrc.lock.{ExclusiveTimePeriodLock, LockRepository}
import uk.gov.hmrc.metrix.domain.{MetricRepository, MetricSource, PersistedMetric}
import uk.gov.hmrc.metrix.persistence.MongoMetricRepository
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class MetricOrchestratorSpec extends UnitSpec
  with ScalaFutures
  with Eventually
  with LoneElement
  with MockitoSugar
  with MongoSpecSupport
  with IntegrationPatience
  with BeforeAndAfterEach {

  val metricRegistry = new MetricRegistry()

  private val exclusiveTimePeriodLock = new ExclusiveTimePeriodLock {
    override val lockId: String = "test-metrics"
    override val repo: LockRepository = new LockRepository()
    override val holdLockFor = Duration.millis(0)
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    mongoMetricRepository.removeAll().futureValue
    metricRegistry.removeMatching(new MetricFilter {
      override def matches(name: String, metric: Metric): Boolean = true
    })
  }

  override def afterEach(): Unit = {
    super.afterEach()
    mongoMetricRepository.removeAll().futureValue
  }

  private val mongoMetricRepository = new MongoMetricRepository

  def metricOrchestratorFor(sources: List[MetricSource],
                            metricRepository: MetricRepository = mongoMetricRepository) = new MetricOrchestrator(
    metricSources = sources,
    lock = exclusiveTimePeriodLock,
    metricRepository = metricRepository,
    metricRegistry = metricRegistry
  )

  def persistedMetricsFrom(metricsMap: Map[String, Int]): Seq[PersistedMetric] =
    metricsMap.map { case (name, count) => PersistedMetric(name, count) }.
      toSeq

  def sourceReturning(metricsMap: Map[String, Int]): MetricSource = {
    new MetricSource {
      override def metrics(implicit ec: ExecutionContext) = {
        Future.successful(metricsMap)
      }
    }
  }

  def sourceReturningFirstAndThen(firstMetricsMap: Map[String, Int],
                                  secondMetricsMap: Map[String, Int]): MetricSource = {
    new MetricSource {
      var iteration = 0

      override def metrics(implicit ec: ExecutionContext) = {
        if (iteration % 2 == 0) {
          iteration += 1
          Future.successful(firstMetricsMap)
        }
        else {
          iteration += 1
          Future.successful(secondMetricsMap)
        }
      }
    }
  }

  "metric orchestrator" should {

    "register all the gauges" in {

      val acquiredMetrics = Map("a" -> 1, "b" -> 2)

      val orchestrator = metricOrchestratorFor(List(sourceReturning(acquiredMetrics)))

      // when
      orchestrator.attemptToUpdateAndRefreshMetrics().futureValue shouldResultIn MetricsUpdatedAndRefreshed(
        acquiredMetrics,
        persistedMetricsFrom(acquiredMetrics)
      )

      metricRegistry.getGauges.get(s"a").getValue shouldBe 1
      metricRegistry.getGauges.get(s"b").getValue shouldBe 2
    }

    "be calculated across multiple sources" in {
      val acquiredMetrics = Map("a" -> 1, "b" -> 2)
      val otherAcquiredMetrics = Map("z" -> 3, "x" -> 4)

      val orchestrator = metricOrchestratorFor(List(
        sourceReturning(acquiredMetrics),
        sourceReturning(otherAcquiredMetrics)
      ))

      // when
      orchestrator.attemptToUpdateAndRefreshMetrics().futureValue shouldResultIn MetricsUpdatedAndRefreshed(
        acquiredMetrics ++ otherAcquiredMetrics,
        persistedMetricsFrom(acquiredMetrics ++ otherAcquiredMetrics)
      )

      metricRegistry.getGauges.get(s"a").getValue shouldBe 1
      metricRegistry.getGauges.get(s"b").getValue shouldBe 2
      metricRegistry.getGauges.get(s"z").getValue shouldBe 3
      metricRegistry.getGauges.get(s"x").getValue shouldBe 4

    }

    "update the metrics when the source changes" in {
      val firstMetrics = Map("metric1" -> 32, "metric2" -> 43)
      val secondMetrics = Map("metric1" -> 11, "metric2" -> 87, "metric3" -> 22)
      val orchestrator = metricOrchestratorFor(List(
        sourceReturningFirstAndThen(firstMetrics, secondMetrics)
      ))

      // when
      orchestrator.attemptToUpdateAndRefreshMetrics().futureValue

      metricRegistry.getGauges.get("metric1").getValue shouldBe 32
      metricRegistry.getGauges.get("metric2").getValue shouldBe 43

      // when
      orchestrator.attemptToUpdateAndRefreshMetrics().futureValue

      metricRegistry.getGauges.get("metric1").getValue shouldBe 11
      metricRegistry.getGauges.get("metric2").getValue shouldBe 87
      metricRegistry.getGauges.get("metric3").getValue shouldBe 22
    }

    "skip reporting all the metrics matching when the skip filter matches all" in {
      val acquiredMetrics = Map("opened.name" -> 4, "ravaged.name" -> 2, "not.ravaged.name" -> 8)
      val orchestrator = metricOrchestratorFor(List(sourceReturning(acquiredMetrics)))

      orchestrator.attemptToUpdateAndRefreshMetrics(
        skipReportingOn = (metric: PersistedMetric) => true
      ).futureValue shouldResultIn MetricsUpdatedAndRefreshed(acquiredMetrics, Seq.empty)

      metricRegistry.getGauges shouldBe empty
    }

    "skip reporting the metrics matching the specific skip filter" in {
      val openedMetricName = "opened.name"
      val notRavagedMetricName = "not.ravaged.name"
      val acquiredMetrics = Map(openedMetricName -> 4, "ravaged.name" -> 2, notRavagedMetricName -> 8)
      val orchestrator = metricOrchestratorFor(List(sourceReturning(acquiredMetrics)))

      orchestrator.attemptToUpdateAndRefreshMetrics(skipReportingOn = (metric: PersistedMetric) => {
        metric.name.contains("ravaged") && metric.count < 3
      }).futureValue shouldResultIn MetricsUpdatedAndRefreshed(
        acquiredMetrics,
        List(PersistedMetric(openedMetricName, 4), PersistedMetric(notRavagedMetricName, 8))
      )

      metricRegistry.getGauges should have size 2
      metricRegistry.getGauges.get(openedMetricName).getValue shouldBe 4
      metricRegistry.getGauges.get(notRavagedMetricName).getValue shouldBe 8
    }

    "not reset value if metrics matching filter when a new value is provided" in {
      val otherMetricName = "opened.name"
      val notResetedMetricName = "not.reseted.name"
      val resetableButProvidedMetricName = "reseted.name"

      val acquiredMetrics = Map(otherMetricName -> 4, resetableButProvidedMetricName -> 2, notResetedMetricName -> 8)
      val orchestrator = metricOrchestratorFor(List(sourceReturning(acquiredMetrics)))

      orchestrator.attemptToUpdateRefreshAndResetMetrics(resetMetricOn = m => m.name == resetableButProvidedMetricName).futureValue shouldResultIn
      MetricsUpdatedAndRefreshed(
        acquiredMetrics,
        List(PersistedMetric(otherMetricName, 4), PersistedMetric(resetableButProvidedMetricName, 2), PersistedMetric(notResetedMetricName, 8))
      )

      metricRegistry.getGauges should have size 3
      metricRegistry.getGauges.get(otherMetricName).getValue shouldBe 4
      metricRegistry.getGauges.get(resetableButProvidedMetricName).getValue shouldBe 2
      metricRegistry.getGauges.get(notResetedMetricName).getValue shouldBe 8
    }

    "reset value if metrics matching reset filter and no metric is provided" in {
      val otherMetricName = "opened.name"
      val notResetedMetricName = "not.reseted.name"
      val resetableMetricName = "reseted.name"

      val mockMetricSource = mock[MetricSource]
      val orchestrator = metricOrchestratorFor(List(mockMetricSource))

      val acquiredMetrics = Map(otherMetricName -> 4, resetableMetricName -> 2, notResetedMetricName -> 8)
      when(mockMetricSource.metrics(any())).thenReturn(Future.successful(acquiredMetrics))
      orchestrator.attemptToUpdateRefreshAndResetMetrics(resetMetricOn = (metric: PersistedMetric) => {
        metric.name == "reseted.name"
      }).futureValue

      val newAcquiredMetrics = Map(otherMetricName -> 5, notResetedMetricName -> 6)
      when(mockMetricSource.metrics(any())).thenReturn(Future.successful(newAcquiredMetrics))
      orchestrator.attemptToUpdateRefreshAndResetMetrics(resetMetricOn = (metric: PersistedMetric) => {
        metric.name == "reseted.name"
      }).futureValue

      metricRegistry.getGauges should have size 3
      metricRegistry.getGauges.get(otherMetricName).getValue shouldBe 5
      metricRegistry.getGauges.get(resetableMetricName).getValue shouldBe 0
      metricRegistry.getGauges.get(notResetedMetricName).getValue shouldBe 6
    }

    "cache the metrics" in {
      val acquiredMetrics = Map("a" -> 1, "b" -> 2)

      val metricRepository: MetricRepository = mock[MetricRepository]

      val orchestrator = new MetricOrchestrator(
        metricRepository = metricRepository,
        metricSources = List(sourceReturning(acquiredMetrics)),
        lock = exclusiveTimePeriodLock,
        metricRegistry = metricRegistry
      )

      when(metricRepository.findAll()(any[ExecutionContext]))
        .thenReturn(Future(List(PersistedMetric("a", 1), PersistedMetric("b", 2), PersistedMetric("z", 8))))

      when(metricRepository.persist(any[PersistedMetric])(any[ExecutionContext]))
        .thenReturn(Future[Unit]())

      // when
      orchestrator.attemptToUpdateAndRefreshMetrics().futureValue shouldResultIn MetricsUpdatedAndRefreshed(
        acquiredMetrics,
        persistedMetricsFrom(acquiredMetrics) :+ PersistedMetric("z", 8)
      )

      verify(metricRepository).findAll()(any[ExecutionContext])
      verify(metricRepository, times(2)).persist(any[PersistedMetric])(any[ExecutionContext])

      metricRegistry.getGauges.get(s"a").getValue shouldBe 1
      metricRegistry.getGauges.get(s"b").getValue shouldBe 2

      verifyNoMoreInteractions(metricRepository)
    }

    "update the cache even if the lock is not acquired" in {
      val mockedMetricRepository: MetricRepository = mock[MetricRepository]

      val lockMock = new ExclusiveTimePeriodLock {
        override val lockId: String = "test-lock"
        override val repo: LockRepository = mock[LockRepository]
        override val holdLockFor: Duration = Duration.millis(1)
      }

      val orchestrator = new MetricOrchestrator(
        metricRepository = mockedMetricRepository,
        metricSources = List(sourceReturning(Map("a" -> 1, "b" -> 2))),
        lock = lockMock,
        metricRegistry = metricRegistry
      )

      when(lockMock.repo.renew(any[String], any[String], any[Duration])).thenReturn(Future(false))
      when(lockMock.repo.lock(any[String], any[String], any[Duration])).thenReturn(Future(false))

      when(mockedMetricRepository.findAll()(any[ExecutionContext]))
        .thenReturn(Future(List(PersistedMetric("a", 4), PersistedMetric("b", 5))))

      // when
      orchestrator.attemptToUpdateAndRefreshMetrics().futureValue shouldResultIn MetricsOnlyRefreshed(
        List(PersistedMetric("a", 4), PersistedMetric("b", 5))
      )

      verify(mockedMetricRepository).findAll()(any[ExecutionContext])

      metricRegistry.getGauges.get(s"a").getValue shouldBe 4
      metricRegistry.getGauges.get(s"b").getValue shouldBe 5

      verifyNoMoreInteractions(mockedMetricRepository)
    }


    "gauges are registered after all metrics are written to mongo even if writing takes a long time" in {

      val acquiredMetrics = Map("a" -> 1, "b" -> 2)

      val orchestrator = metricOrchestratorFor(
        sources = List(sourceReturning(acquiredMetrics)),
        metricRepository = new SlowlyWritingMetricRepository
      )

      // when
      orchestrator.attemptToUpdateAndRefreshMetrics().futureValue shouldResultIn MetricsUpdatedAndRefreshed(
        acquiredMetrics,
        persistedMetricsFrom(acquiredMetrics)
      )

      metricRegistry.getGauges.get(s"a").getValue shouldBe 1
      metricRegistry.getGauges.get(s"b").getValue shouldBe 2
    }

    class SlowlyWritingMetricRepository extends MongoMetricRepository {
      override def persist(calculatedMetric: PersistedMetric)(implicit ec: ExecutionContext): Future[Unit] = {
        Future(Thread.sleep(200)).flatMap(_ => super.persist(calculatedMetric))
      }
    }

    implicit class MetricOrchestrationResultComparison(metricUpdateResult: MetricOrchestrationResult) {
      def shouldResultIn(expectedUpdateResult: MetricsOnlyRefreshed): Unit = {
        inside(metricUpdateResult) {
          case MetricsOnlyRefreshed(refreshedMetrics) =>
            refreshedMetrics should contain theSameElementsAs expectedUpdateResult.refreshedMetrics
        }
      }

      def shouldResultIn(expectedUpdateResult: MetricsUpdatedAndRefreshed): Unit = {
        inside(metricUpdateResult) {
          case MetricsUpdatedAndRefreshed(updatedMetrics, refreshedMetrics) =>
            updatedMetrics shouldBe expectedUpdateResult.updatedMetrics
            refreshedMetrics should contain theSameElementsAs expectedUpdateResult.refreshedMetrics
        }
      }
    }
  }
}
