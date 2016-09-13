package uk.gov.hmrc.metrix.domain

import scala.concurrent.{ExecutionContext, Future}

trait MetricSource {
  def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]]
}
