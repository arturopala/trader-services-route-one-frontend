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

package uk.gov.hmrc.uploaddocuments.controllers

import play.api.i18n.I18nSupport
import play.api.mvc._
import play.api.{Configuration, Environment}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController
import uk.gov.hmrc.play.fsm.{JourneyController, JourneyService}
import uk.gov.hmrc.uploaddocuments.connectors.FrontendAuthConnector
import uk.gov.hmrc.uploaddocuments.support.SHA256
import uk.gov.hmrc.uploaddocuments.wiring.AppConfig

import scala.concurrent.{ExecutionContext, Future}

/** Base controller class for a journey. */
abstract class BaseJourneyController[S <: JourneyService[HeaderCarrier]](
  val journeyService: S,
  controllerComponents: MessagesControllerComponents,
  appConfig: AppConfig,
  val authConnector: FrontendAuthConnector,
  val env: Environment,
  val config: Configuration
) extends FrontendController(controllerComponents) with I18nSupport with AuthActions
    with JourneyController[HeaderCarrier] {

  final override val actionBuilder = controllerComponents.actionBuilder
  final override val messagesApi = controllerComponents.messagesApi

  implicit val ec: ExecutionContext = controllerComponents.executionContext

  /** AsAnyUser authorisation request */
  final val AsAnyUser: WithAuthorised[Option[String]] =
    if (appConfig.requireEnrolmentFeature) { implicit request => body =>
      authorisedWithEnrolment(
        appConfig.authorisedServiceName,
        appConfig.authorisedIdentifierKey
      )(x => body(x._2))
    } else { implicit request => body =>
      authorisedWithoutEnrolment(x => body(x._2))
    }

  /** Base authorized action builder */
  final val whenAuthenticated = actions.whenAuthorised(AsAnyUser)

  // ------------------------------------
  // Retrieval of journeyId configuration
  // ------------------------------------

  private val journeyIdPathParamRegex = ".*?/journey/([a-fA-F0-9]+?)/.*".r

  final def journeyId(implicit rh: RequestHeader): Option[String] =
    (rh.path match {
      case journeyIdPathParamRegex(id) => Some(id)
      case _                           => None
    })
      .orElse(hc.sessionId.map(_.value).map(SHA256.compute))

  final def currentJourneyId(implicit rh: RequestHeader): String = journeyId.get

  final override implicit def context(implicit rh: RequestHeader): HeaderCarrier = {
    val headerCarrier = super.hc(rh)
    journeyId
      .map(value => headerCarrier.withExtraHeaders(journeyService.journeyKey -> value))
      .getOrElse(headerCarrier)
  }

  final override def withValidRequest(
    body: => Future[Result]
  )(implicit rc: HeaderCarrier, request: Request[_], ec: ExecutionContext): Future[Result] =
    journeyId match {
      case None => Future.successful(Redirect(appConfig.govukStartUrl))
      case _    => body
    }
}
