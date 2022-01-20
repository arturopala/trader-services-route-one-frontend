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

package uk.gov.hmrc.uploaddocuments.connectors

import akka.actor.ActorSystem
import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http._
import uk.gov.hmrc.uploaddocuments.models.{Nonce, UploadedFile}
import uk.gov.hmrc.uploaddocuments.wiring.AppConfig

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import uk.gov.hmrc.uploaddocuments.models.FileUploadSessionConfig
import uk.gov.hmrc.uploaddocuments.models.FileUploads
import play.api.libs.json.JsValue

/** Connector to push the results of the file uploads back to the host service. */
@Singleton
class FileUploadResultPushConnector @Inject() (
  appConfig: AppConfig,
  http: HttpPost,
  metrics: Metrics,
  val actorSystem: ActorSystem
) extends HttpAPIMonitor with Retries {

  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  import FileUploadResultPushConnector._

  def push(request: Request)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Response] =
    retry(appConfig.fileUploadResultPushRetryIntervals: _*)(shouldRetry, errorMessage) {
      monitor(s"ConsumedAPI-push-file-uploads-${request.hostServiceId}-POST") {
        val endpointUrl = new URL(request.url).toExternalForm
        http
          .POST[Payload, HttpResponse](endpointUrl, Payload.from(request))
          .transformWith[Response] {
            case Success(response) =>
              Future.successful(
                if (response.status == 204) SuccessResponse
                else Left(Error(response.status, s"Failure to push to ${request.url}: ${response.body}"))
              )
            case Failure(exception) =>
              Future.successful(Left(Error(0, exception.getMessage())))
          }
      }
    }

}

object FileUploadResultPushConnector {

  case class Request(
    hostServiceId: String,
    url: String,
    nonce: Nonce,
    uploadedFiles: Seq[UploadedFile],
    context: Option[JsValue]
  )
  case class Payload(nonce: Nonce, uploadedFiles: Seq[UploadedFile], context: Option[JsValue])

  type Response = Either[FileUploadResultPushConnector.Error, Unit]

  val SuccessResponse: Response = Right[FileUploadResultPushConnector.Error, Unit](())

  case class Error(status: Int, message: String) {
    def shouldRetry: Boolean = (status >= 500 && status < 600) || status == 499
  }

  object Request {
    def from(config: FileUploadSessionConfig, fileUploads: FileUploads): Request =
      Request(config.serviceId, config.resultPostUrl, config.nonce, fileUploads.toUploadedFiles, config.cargo)

    implicit val format: Format[Request] = Json.format[Request]
  }

  object Payload {
    def from(request: Request): Payload =
      Payload(request.nonce, request.uploadedFiles, request.context)

    def from(config: FileUploadSessionConfig, fileUploads: FileUploads): Payload =
      Payload.from(Request.from(config, fileUploads))

    implicit val format: Format[Payload] = Json.format[Payload]
  }

  final def shouldRetry(response: Try[Response]): Boolean =
    response match {
      case Success(response)  => response.left.exists(_.shouldRetry)
      case Failure(exception) => false
    }

  final def errorMessage(response: Response): String =
    s"Error ${response.left.map(e => s"status=${e.status} message=${e.message}").left.getOrElse("")}"

}
