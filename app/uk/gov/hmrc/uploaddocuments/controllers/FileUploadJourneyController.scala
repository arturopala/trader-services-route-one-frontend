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

import akka.actor.{ActorSystem, Scheduler}
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.Json
import play.api.mvc._
import play.api.{Configuration, Environment}
import play.mvc.Http.HeaderNames
import uk.gov.hmrc.uploaddocuments.connectors._
import uk.gov.hmrc.uploaddocuments.models._
import uk.gov.hmrc.uploaddocuments.services.FileUploadJourneyServiceWithHeaderCarrier
import uk.gov.hmrc.uploaddocuments.views.UploadFileViewContext
import uk.gov.hmrc.uploaddocuments.wiring.AppConfig

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import com.fasterxml.jackson.core.JsonParseException

@Singleton
class FileUploadJourneyController @Inject() (
  fileUploadJourneyService: FileUploadJourneyServiceWithHeaderCarrier,
  views: uk.gov.hmrc.uploaddocuments.views.FileUploadViews,
  upscanInitiateConnector: UpscanInitiateConnector,
  fileUploadResultPushConnector: FileUploadResultPushConnector,
  uploadFileViewContext: UploadFileViewContext,
  appConfig: AppConfig,
  authConnector: FrontendAuthConnector,
  environment: Environment,
  configuration: Configuration,
  controllerComponents: MessagesControllerComponents,
  val actorSystem: ActorSystem
) extends BaseJourneyController(
      fileUploadJourneyService,
      controllerComponents,
      appConfig,
      authConnector,
      environment,
      configuration
    ) with FileStream {

  final val controller = routes.FileUploadJourneyController

  import FileUploadJourneyController._
  import uk.gov.hmrc.uploaddocuments.journeys.FileUploadJourneyModel._

  implicit val scheduler: Scheduler = actorSystem.scheduler

  // POST /initialize
  final val initialize: Action[AnyContent] =
    actions
      .parseJsonWithFallback[FileUploadInitializationRequest](BadRequest)
      .applyWithRequest { implicit request =>
        Transitions.initialize(CallbackAuth.from(request))
      }
      .displayUsing(renderInitializationResponse)
      .recover {
        case e: JsonParseException => BadRequest(e.getMessage())
        case e                     => InternalServerError
      }

  // GET /continue-to-host
  final val continueToHost: Action[AnyContent] =
    whenAuthenticated
      .show[State.ContinueToHost]
      .orApply(Transitions.continueToHost)

  // ----------------------- FILES UPLOAD -----------------------

  /** Initial time to wait for callback arrival. */
  final val INITIAL_CALLBACK_WAIT_TIME_SECONDS = 2

  /** This cookie is set by the script on each request coming from one of our own pages open in the browser.
    */
  final val COOKIE_JSENABLED = "jsenabled"

  final def preferUploadMultipleFiles(implicit rh: RequestHeader): Boolean =
    rh.cookies.get(COOKIE_JSENABLED).isDefined

  final def successRedirect(implicit rh: RequestHeader) =
    appConfig.baseExternalCallbackUrl + (rh.cookies.get(COOKIE_JSENABLED) match {
      case Some(_) => controller.asyncWaitingForFileVerification(journeyId.get)
      case None    => controller.showWaitingForFileVerification
    })

  final def successRedirectWhenUploadingMultipleFiles(implicit rh: RequestHeader) =
    appConfig.baseExternalCallbackUrl + controller.asyncMarkFileUploadAsPosted(journeyId.get)

  final def errorRedirect(implicit rh: RequestHeader) =
    appConfig.baseExternalCallbackUrl + (rh.cookies.get(COOKIE_JSENABLED) match {
      case Some(_) => controller.asyncMarkFileUploadAsRejected(journeyId.get)
      case None    => controller.markFileUploadAsRejected
    })

  final def upscanRequest(nonce: String)(implicit rh: RequestHeader) =
    UpscanInitiateRequest(
      callbackUrl = appConfig.baseInternalCallbackUrl + controller.callbackFromUpscan(currentJourneyId, nonce).url,
      successRedirect = Some(successRedirect),
      errorRedirect = Some(errorRedirect),
      minimumFileSize = Some(1),
      maximumFileSize = Some(appConfig.fileFormats.maxFileSizeMb * 1024 * 1024),
      expectedContentType = Some(appConfig.fileFormats.approvedFileTypes)
    )

  final def upscanRequestWhenUploadingMultipleFiles(nonce: String)(implicit rh: RequestHeader) =
    UpscanInitiateRequest(
      callbackUrl = appConfig.baseInternalCallbackUrl + controller.callbackFromUpscan(currentJourneyId, nonce).url,
      successRedirect = Some(successRedirectWhenUploadingMultipleFiles),
      errorRedirect = Some(errorRedirect),
      minimumFileSize = Some(1),
      maximumFileSize = Some(appConfig.fileFormats.maxFileSizeMb * 1024 * 1024),
      expectedContentType = Some(appConfig.fileFormats.approvedFileTypes)
    )

  // GET /
  final val showUploadMultipleFiles: Action[AnyContent] =
    whenAuthenticated
      .applyWithRequest(implicit request => Transitions.toUploadMultipleFiles(preferUploadMultipleFiles))
      .redirectOrDisplayIf[State.UploadMultipleFiles]

  // POST /initialize-upscan/:uploadId
  final def initiateNextFileUpload(uploadId: String): Action[AnyContent] =
    whenAuthenticated
      .applyWithRequest { implicit request =>
        Transitions
          .initiateNextFileUpload(uploadId)(upscanRequestWhenUploadingMultipleFiles)(
            upscanInitiateConnector.initiate(_, _)
          )
      }
      .displayUsing(renderUploadRequestJson(uploadId))

  // GET /file-upload
  final val showFileUpload: Action[AnyContent] =
    whenAuthenticated
      .applyWithRequest { implicit request =>
        Transitions
          .initiateFileUpload(upscanRequest)(upscanInitiateConnector.initiate(_, _))
      }
      .redirectOrDisplayIf[State.UploadFile]

  // GET /file-rejected
  final val markFileUploadAsRejected: Action[AnyContent] =
    whenAuthenticated
      .bindForm(UpscanUploadErrorForm)
      .apply(Transitions.markUploadAsRejected)

  // POST /new/file-rejected
  final val markFileUploadAsRejectedAsync: Action[AnyContent] =
    whenAuthenticated
      .bindForm(UpscanUploadErrorForm)
      .apply(Transitions.markUploadAsRejected)
      .displayUsing(acknowledgeFileUploadRedirect)

  // GET /journey/:journeyId/file-rejected
  final def asyncMarkFileUploadAsRejected(journeyId: String): Action[AnyContent] =
    actions
      .bindForm(UpscanUploadErrorForm)
      .apply(Transitions.markUploadAsRejected)
      .displayUsing(acknowledgeFileUploadRedirect)

  // GET /file-verification
  final val showWaitingForFileVerification: Action[AnyContent] =
    whenAuthenticated
      .waitForStateThenRedirect[State.FileUploaded](INITIAL_CALLBACK_WAIT_TIME_SECONDS)
      .orApplyOnTimeout(Transitions.waitForFileVerification)
      .redirectOrDisplayIf[State.WaitingForFileVerification]

  // GET /journey/:journeyId/file-verification
  final def asyncWaitingForFileVerification(journeyId: String): Action[AnyContent] =
    actions
      .waitForStateAndDisplayUsing[State.FileUploaded](
        INITIAL_CALLBACK_WAIT_TIME_SECONDS,
        acknowledgeFileUploadRedirect
      )
      .orApplyOnTimeout(Transitions.waitForFileVerification)
      .displayUsing(acknowledgeFileUploadRedirect)

  // OPTIONS
  final def preflightUpload(journeyId: String): Action[AnyContent] =
    Action {
      Created.withHeaders(HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN -> "*")
    }

  // GET /new/journey/:journeyId/file-posted
  final def asyncMarkFileUploadAsPosted(journeyId: String): Action[AnyContent] =
    actions
      .bindForm(UpscanUploadSuccessForm)
      .apply(Transitions.markUploadAsPosted)
      .displayUsing(acknowledgeFileUploadRedirect)

  // POST /journey/:journeyId/callback-from-upscan/:nonce
  final def callbackFromUpscan(journeyId: String, nonce: String): Action[AnyContent] =
    actions
      .parseJsonWithFallback[UpscanNotification](BadRequest)
      .applyWithRequest(implicit request =>
        Transitions
          .upscanCallbackArrived(fileUploadResultPushConnector.push(_))(Nonce(nonce))
      )
      .transform {
        case r if r.header.status < 400 => NoContent
      }
      .recover { case e =>
        InternalServerError
      }

  // GET /uploaded
  final val showFileUploaded: Action[AnyContent] =
    whenAuthenticated
      .show[State.FileUploaded]
      .orApply(Transitions.backToFileUploaded)

  // POST /uploaded
  final val submitUploadAnotherFileChoice: Action[AnyContent] =
    whenAuthenticated
      .bindForm[Boolean](UploadAnotherFileChoiceForm)
      .applyWithRequest { implicit request =>
        Transitions.submitedUploadAnotherFileChoice(upscanRequest)(upscanInitiateConnector.initiate(_, _))(
          Transitions.continueToHost
        )
      }

  // GET /uploaded/:reference/remove
  final def removeFileUploadByReference(reference: String): Action[AnyContent] =
    whenAuthenticated
      .applyWithRequest { implicit request =>
        Transitions.removeFileUploadByReference(reference)(upscanRequest)(
          upscanInitiateConnector.initiate(_, _)
        )(fileUploadResultPushConnector.push(_))
      }

  // POST /uploaded/:reference/remove
  final def removeFileUploadByReferenceAsync(reference: String): Action[AnyContent] =
    whenAuthenticated
      .applyWithRequest { implicit request =>
        Transitions.removeFileUploadByReference(reference)(upscanRequest)(
          upscanInitiateConnector.initiate(_, _)
        )(fileUploadResultPushConnector.push(_))
      }
      .displayUsing(renderFileRemovalStatus)

  // GET /uploaded/:reference/:fileName
  final def previewFileUploadByReference(reference: String, fileName: String): Action[AnyContent] =
    whenAuthenticated.showCurrentState
      .displayAsyncUsing(streamFileFromUspcan(reference))

  // GET /file-verification/:reference/status
  final def checkFileVerificationStatus(reference: String): Action[AnyContent] =
    whenAuthenticated.showCurrentState
      .displayUsing(renderFileVerificationStatus(reference))

  /** Function from the `State` to the `Call` (route), used by play-fsm internally to create redirects.
    */
  final override def getCallFor(state: State)(implicit request: Request[_]): Call =
    state match {
      case State.Initialized(context, fileUploads) =>
        Call("GET", context.config.backlinkUrl)

      case State.ContinueToHost(context, fileUploads) =>
        controller.continueToHost

      case _: State.UploadMultipleFiles =>
        controller.showUploadMultipleFiles

      case _: State.UploadFile =>
        controller.showFileUpload

      case _: State.WaitingForFileVerification =>
        controller.showWaitingForFileVerification

      case _: State.FileUploaded =>
        controller.showFileUploaded

      case _: State.SwitchToSingleFileUpload =>
        controller.showFileUpload

      case _ =>
        Call("GET", appConfig.govukStartUrl)
    }

  import uk.gov.hmrc.play.fsm.OptionalFormOps._

  /** Function from the `State` to the `Result`, used by play-fsm internally to render the actual content.
    */
  final override def renderState(state: State, breadcrumbs: List[State], formWithErrors: Option[Form[_]])(implicit
    request: Request[_]
  ): Result =
    state match {
      case State.Uninitialized =>
        Redirect(appConfig.govukStartUrl)

      case State.Initialized(config, fileUploads) =>
        if (preferUploadMultipleFiles)
          Redirect(controller.showUploadMultipleFiles)
        else
          Redirect(controller.showFileUpload)

      case State.ContinueToHost(context, fileUploads) =>
        Redirect(context.config.continueUrl)

      case State.UploadMultipleFiles(model, fileUploads) =>
        Ok(
          views.uploadMultipleFilesView(
            maxFileUploadsNumber,
            fileUploads.files,
            initiateNextFileUpload = controller.initiateNextFileUpload,
            checkFileVerificationStatus = controller.checkFileVerificationStatus,
            removeFile = controller.removeFileUploadByReferenceAsync,
            previewFile = controller.previewFileUploadByReference,
            markFileRejected = controller.markFileUploadAsRejectedAsync,
            continueAction = controller.continueToHost,
            backLink = backLinkFor(breadcrumbs)
          )
        )

      case State.UploadFile(model, reference, uploadRequest, fileUploads, maybeUploadError) =>
        Ok(
          views.uploadFileView(
            uploadRequest,
            fileUploads,
            maybeUploadError,
            successAction = controller.showFileUploaded,
            failureAction = controller.showFileUpload,
            checkStatusAction = controller.checkFileVerificationStatus(reference),
            backLink = backLinkFor(breadcrumbs)
          )
        )

      case State.WaitingForFileVerification(_, reference, _, _, _) =>
        Ok(
          views.waitingForFileVerificationView(
            successAction = controller.showFileUploaded,
            failureAction = controller.showFileUpload,
            checkStatusAction = controller.checkFileVerificationStatus(reference),
            backLink = backLinkFor(breadcrumbs)
          )
        )

      case State.FileUploaded(model, fileUploads, _) =>
        Ok(
          if (fileUploads.acceptedCount < maxFileUploadsNumber)
            views.fileUploadedView(
              formWithErrors.or(UploadAnotherFileChoiceForm),
              fileUploads,
              controller.submitUploadAnotherFileChoice,
              controller.previewFileUploadByReference,
              controller.removeFileUploadByReference,
              backLinkFor(breadcrumbs)
            )
          else
            views.fileUploadedSummaryView(
              fileUploads,
              controller.continueToHost,
              controller.previewFileUploadByReference,
              controller.removeFileUploadByReference,
              backLinkFor(breadcrumbs)
            )
        )

      case _ => NotImplemented
    }

  private def renderUploadRequestJson(
    uploadId: String
  ) =
    Renderer.simple {
      case s: State.UploadMultipleFiles =>
        s.fileUploads
          .findReferenceAndUploadRequestForUploadId(uploadId) match {
          case Some((reference, uploadRequest)) =>
            val json =
              Json.obj(
                "upscanReference" -> reference,
                "uploadId"        -> uploadId,
                "uploadRequest"   -> UploadRequest.formats.writes(uploadRequest)
              )
            Ok(json)

          case None => NotFound
        }

      case _ => Forbidden
    }

  private def renderFileVerificationStatus(
    reference: String
  ) =
    Renderer.withRequest { implicit request =>
      {
        case s: FileUploadState =>
          s.fileUploads.files.find(_.reference == reference) match {
            case Some(file) =>
              Ok(
                Json.toJson(
                  FileVerificationStatus(
                    file,
                    uploadFileViewContext,
                    controller.previewFileUploadByReference(_, _),
                    appConfig.fileFormats.maxFileSizeMb
                  )
                )
              )
            case None => NotFound
          }
        case _ => NotFound
      }
    }

  private def renderInitializationResponse =
    Renderer.simple {
      case s: State.Initialized => Created
      case _                    => BadRequest
    }

  private def renderFileRemovalStatus =
    Renderer.simple {
      case s: State => NoContent
      case _        => BadRequest
    }

  private def streamFileFromUspcan(
    reference: String
  ) =
    AsyncRenderer.simple {
      case s: FileUploadState =>
        s.fileUploads.files.find(_.reference == reference) match {
          case Some(file: FileUpload.Accepted) =>
            getFileStream(
              file.url,
              file.fileName,
              file.fileMimeType,
              (fileName, fileMimeType) =>
                fileMimeType match {
                  case _ =>
                    HeaderNames.CONTENT_DISPOSITION ->
                      s"""inline; filename="${fileName.filter(_.toInt < 128)}"; filename*=utf-8''${RFC3986Encoder
                        .encode(fileName)}"""
                }
            )

          case _ => Future.successful(NotFound)
        }
      case _ => Future.successful(NotFound)

    }

  private def acknowledgeFileUploadRedirect = Renderer.simple { case state =>
    (state match {
      case _: State.UploadMultipleFiles        => Created
      case _: State.FileUploaded               => Created
      case _: State.WaitingForFileVerification => Accepted
      case _                                   => NoContent
    }).withHeaders(HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN -> "*")
  }

}

object FileUploadJourneyController {

  import FormFieldMappings._

  val UploadAnotherFileChoiceForm = Form[Boolean](
    mapping("uploadAnotherFile" -> uploadAnotherFileMapping)(identity)(Option.apply)
  )

  val UpscanUploadSuccessForm = Form[S3UploadSuccess](
    mapping(
      "key"    -> nonEmptyText,
      "bucket" -> optional(nonEmptyText)
    )(S3UploadSuccess.apply)(S3UploadSuccess.unapply)
  )

  val UpscanUploadErrorForm = Form[S3UploadError](
    mapping(
      "key"            -> nonEmptyText,
      "errorCode"      -> text,
      "errorMessage"   -> text,
      "errorRequestId" -> optional(text),
      "errorResource"  -> optional(text)
    )(S3UploadError.apply)(S3UploadError.unapply)
  )

}
