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

import com.codahale.metrics.MetricRegistry
import org.joda.time.Duration
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.LoneElement
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.mock.MockitoSugar
import uk.gov.hmrc.lock.{ExclusiveTimePeriodLock, LockRepository}
import uk.gov.hmrc.metrix.domain.{MetricSource, MetricCount, MetricRepository}
import uk.gov.hmrc.metrix.persistence.MongoMetricRepository
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class MetricOrchestatorSpec extends UnitSpec
with ScalaFutures
with Eventually
with LoneElement
with MockitoSugar
with MongoSpecSupport
with IntegrationPatience {

  val testRegistry = new MetricRegistry()

  private val exclusiveTimePeriodLock = new ExclusiveTimePeriodLock {
    override val lockId: String = "test-metrics"
    override val repo: LockRepository = new LockRepository()
    override val holdLockFor = Duration.millis(0)
  }

  def mongoMetricRegistryFor(sources: List[MetricSource]) = new MetricOrchestrator(
    metricSources = sources,
    lock = exclusiveTimePeriodLock,
    metricRepository = new MongoMetricRepository,
    metricRegistry = testRegistry
  )


  "mongo metrics registry" should {

    "refresh all metrics of any type of source" in {

      val anySource = new MetricSource {
        override def metrics(implicit ec: ExecutionContext) = Future.successful(Map("a" -> 1, "b" -> 2))
      }

      val registry = mongoMetricRegistryFor(List(anySource))

      registry.refreshAll().futureValue

      testRegistry.getGauges.get(s"a").getValue shouldBe 1
      testRegistry.getGauges.get(s"b").getValue shouldBe 2

    }

    "return all metrics in the registry" in {

      val anySource = new MetricSource {
        override def metrics(implicit ec: ExecutionContext) = Future.successful(Map("a" -> 1, "b" -> 2))
      }

      val registry = mongoMetricRegistryFor(List(anySource))

      val allMetrics: Map[String, Int] = registry.refreshAll().futureValue

      allMetrics(s"a") shouldBe 1
      allMetrics(s"b") shouldBe 2

    }

    "be calculated across multiple soruces" in {

      val anySource = new MetricSource {
        override def metrics(implicit ec: ExecutionContext) = Future.successful(Map("a" -> 1, "b" -> 2))
      }

      val anotherSource = new MetricSource {
        override def metrics(implicit ec: ExecutionContext) = Future.successful(Map("z" -> 3, "x" -> 4))
      }

      val registry = mongoMetricRegistryFor(List(anySource, anotherSource))

      registry.refreshAll().futureValue

      testRegistry.getGauges.get(s"a").getValue shouldBe 1
      testRegistry.getGauges.get(s"b").getValue shouldBe 2
      testRegistry.getGauges.get(s"z").getValue shouldBe 3
      testRegistry.getGauges.get(s"x").getValue shouldBe 4

    }

    "cache the metrics" in {
      val anySource = new MetricSource {
        override def metrics(implicit ec: ExecutionContext) = Future.successful(Map("a" -> 1, "b" -> 2))
      }

      val metricRepository: MetricRepository = mock[MetricRepository]

      val mockedRegistry = new MetricOrchestrator(
        metricRepository = metricRepository,
        metricSources = List(anySource),
        lock = exclusiveTimePeriodLock,
        metricRegistry = testRegistry
      )

      when(metricRepository.findAll()(any[ExecutionContext]))
        .thenReturn(Future(List(MetricCount("a", 1), MetricCount("b", 2))))

      when(metricRepository.persist(any[MetricCount])(any[ExecutionContext]))
        .thenReturn(Future[Unit]())

      // when
      mockedRegistry.refreshAll().futureValue

      verify(metricRepository).findAll()(any[ExecutionContext])
      verify(metricRepository, times(2)).persist(any[MetricCount])(any[ExecutionContext])

      testRegistry.getGauges.get(s"a").getValue shouldBe 1
      testRegistry.getGauges.get(s"b").getValue shouldBe 2

      verifyNoMoreInteractions(metricRepository)
    }

    "update the cache even if the lock is not acquired" in {
      val anySource = new MetricSource {
        override def metrics(implicit ec: ExecutionContext) = Future.successful(Map("a" -> 1, "b" -> 2))
      }

      val metricRepository: MetricRepository = mock[MetricRepository]

      val lockMock = new ExclusiveTimePeriodLock {
        override val lockId: String = "test-lock"
        override val repo: LockRepository = mock[LockRepository]
        override val holdLockFor: Duration = Duration.millis(1)
      }

      val theMetrix = new MetricOrchestrator(
        metricRepository = metricRepository,
        metricSources = List(anySource),
        lock = lockMock,
        metricRegistry = testRegistry
      )

      when(lockMock.repo.renew(any[String], any[String], any[Duration])).thenReturn(Future(false))
      when(lockMock.repo.lock(any[String], any[String], any[Duration])).thenReturn(Future(false))

      when(metricRepository.findAll()(any[ExecutionContext]))
        .thenReturn(Future(List(MetricCount("a", 1), MetricCount("b", 2))))

      // when
      theMetrix.refreshAll().futureValue

      verify(metricRepository).findAll()(any[ExecutionContext])

      testRegistry.getGauges.get(s"a").getValue shouldBe 1
      testRegistry.getGauges.get(s"b").getValue shouldBe 2

      verifyNoMoreInteractions(metricRepository)
    }
  }
}

