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
import uk.gov.hmrc.play.bootstrap.controller.{Utf8MimeTypes, WithJsonBody}
import uk.gov.hmrc.play.fsm.{JourneyController, JourneyService}
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import uk.gov.hmrc.uploaddocuments.connectors.FrontendAuthConnector
import uk.gov.hmrc.uploaddocuments.support.SHA256
import uk.gov.hmrc.uploaddocuments.wiring.AppConfig

import scala.concurrent.{ExecutionContext, Future}

/** Base controller class for a journey. */
abstract class BaseJourneyController[S <: JourneyService[HeaderCarrier]](
  val journeyService: S,
  appConfig: AppConfig,
  val authConnector: FrontendAuthConnector,
  val env: Environment,
  val config: Configuration
) extends MessagesBaseController with Utf8MimeTypes with WithJsonBody with I18nSupport with AuthActions
    with JourneyController[HeaderCarrier] {

  final override val actionBuilder = controllerComponents.actionBuilder
  final override val messagesApi = controllerComponents.messagesApi

  implicit val ec: ExecutionContext = controllerComponents.executionContext

  final val AsFrontendUser: WithAuthorised[Unit] = { implicit request => body =>
    val hc = HeaderCarrierConverter.fromRequestAndSession(request, request.session)
    authorisedWithoutEnrolment(_ => body(()))(request, hc, ec)
  }

  final val whenAuthenticated = actions.whenAuthorised(AsFrontendUser)

  final val AsBackchannelUser: WithAuthorised[Unit] = { implicit request => body =>
    val hc = HeaderCarrierConverter.fromRequest(request)
    authorisedWithoutEnrolment(_ => body(()))(request, hc, ec)
  }

  final val whenAuthenticatedInBackchannel = actions.whenAuthorised(AsBackchannelUser)

  // ------------------------------------
  // Retrieval of journeyId configuration
  // ------------------------------------

  private val journeyIdPathParamRegex = ".*?/journey/([a-fA-F0-9]+?)/.*".r

  final def currentJourneyId(implicit rh: RequestHeader): String = journeyId.get

  final def journeyId(implicit rh: RequestHeader): Option[String] =
    journeyId(decodeHeaderCarrier(rh), rh)

  private def journeyId(hc: HeaderCarrier, rh: RequestHeader): Option[String] =
    (rh.path match {
      case journeyIdPathParamRegex(id) => Some(id)
      case _                           => None
    })
      .orElse(hc.sessionId.map(_.value).map(SHA256.compute))

  final override implicit def context(implicit rh: RequestHeader): HeaderCarrier = {
    val hc = decodeHeaderCarrier(rh)
    journeyId(hc, rh)
      .map(jid => hc.withExtraHeaders(journeyService.journeyKey -> jid))
      .getOrElse(hc)
  }

  private def decodeHeaderCarrier(rh: RequestHeader): HeaderCarrier =
    HeaderCarrierConverter.fromRequestAndSession(rh, rh.session)

  final override def withValidRequest(
    body: => Future[Result]
  )(implicit rc: HeaderCarrier, request: Request[_], ec: ExecutionContext): Future[Result] =
    journeyId match {
      case None => Future.successful(Redirect(appConfig.govukStartUrl))
      case _    => body
    }
}
