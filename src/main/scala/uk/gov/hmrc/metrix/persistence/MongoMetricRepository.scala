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

package uk.gov.hmrc.metrix.persistence

import play.api.libs.json.{JsObject, Json}
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.api.{DB, ReadPreference}
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.metrix.domain.{MetricRepository, PersistedMetric}
import uk.gov.hmrc.mongo.ReactiveRepository
import reactivemongo.play.json.ImplicitBSONHandlers._

import scala.concurrent.{ExecutionContext, Future}

class MongoMetricRepository(collectionName: String = "metrics")(implicit mongo: () => DB)
  extends ReactiveRepository[PersistedMetric, BSONObjectID](collectionName, mongo, PersistedMetric.format)
  with MetricRepository {

  override def indexes: Seq[Index] = Seq(
    Index(key = Seq("name" -> IndexType.Ascending), name = Some("metric_key_idx"), unique = true, background = true)
  )

  override def findAll()(implicit ec: ExecutionContext): Future[List[PersistedMetric]] =
    findAll(ReadPreference.secondaryPreferred)

  def persist(calculatedMetric: PersistedMetric)(implicit ec: ExecutionContext) =
    collection.findAndUpdate(
      selector = Json.obj("name" -> calculatedMetric.name),
      update = Json.toJson(calculatedMetric).as[JsObject],
      upsert = true,
      fetchNewObject = true
    ).map(_.result[PersistedMetric])
}
