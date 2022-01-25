/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.uploaddocuments.services

import javax.inject.{Inject, Singleton}
import play.api.libs.json.Format
import uk.gov.hmrc.crypto.ApplicationCrypto
import uk.gov.hmrc.uploaddocuments.journeys.{FileUploadJourneyModel, FileUploadJourneyStateFormats}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.fsm.PersistentJourneyService
import uk.gov.hmrc.uploaddocuments.wiring.AppConfig
import uk.gov.hmrc.uploaddocuments.repository.CacheRepository
import akka.actor.ActorSystem

trait FileUploadJourneyService[RequestContext] extends PersistentJourneyService[RequestContext] {

  val journeyKey = "FileUploadJourney"

  override val model = FileUploadJourneyModel

  // do not keep errors or transient states in the journey history
  override val breadcrumbsRetentionStrategy: Breadcrumbs => Breadcrumbs =
    _.filter { case s: model.IsTransient => false; case _ => true }
      .take(3) // retain last three states as a breadcrumbs

  override def updateBreadcrumbs(
    newState: model.State,
    currentState: model.State,
    currentBreadcrumbs: Breadcrumbs
  ): Breadcrumbs =
    if (currentState.isInstanceOf[model.IsTransient])
      currentBreadcrumbs
    else if (newState.getClass == currentState.getClass)
      currentBreadcrumbs
    else if (currentBreadcrumbs.nonEmpty && currentBreadcrumbs.head.getClass() == newState.getClass())
      currentBreadcrumbs.tail
    else currentState :: breadcrumbsRetentionStrategy(currentBreadcrumbs)
}

trait FileUploadJourneyServiceWithHeaderCarrier extends FileUploadJourneyService[HeaderCarrier]

@Singleton
case class MongoDBCachedFileUploadJourneyService @Inject() (
  cacheRepository: CacheRepository,
  applicationCrypto: ApplicationCrypto,
  appConfig: AppConfig,
  actorSystem: ActorSystem
) extends MongoDBCachedJourneyService[HeaderCarrier] with FileUploadJourneyServiceWithHeaderCarrier {

  override val stateFormats: Format[model.State] =
    FileUploadJourneyStateFormats.formats

  override def getJourneyId(hc: HeaderCarrier): Option[String] =
    hc.extraHeaders.find(_._1 == journeyKey).map(_._2)

  override val traceFSM: Boolean = appConfig.traceFSM
}
