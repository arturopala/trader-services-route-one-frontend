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
import uk.gov.hmrc.uploaddocuments.views.UploadFileViewHelper
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
  uploadFileViewHelper: UploadFileViewHelper,
  appConfig: AppConfig,
  authConnector: FrontendAuthConnector,
  environment: Environment,
  configuration: Configuration,
  val controllerComponents: MessagesControllerComponents,
  val actorSystem: ActorSystem
) extends BaseJourneyController(
      fileUploadJourneyService,
      appConfig,
      authConnector,
      environment,
      configuration
    ) with FileStream {

  final val controller = routes.FileUploadJourneyController

  import FileUploadJourneyController._
  import uk.gov.hmrc.uploaddocuments.journeys.FileUploadJourneyModel._

  implicit val scheduler: Scheduler = actorSystem.scheduler

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

  final def upscanRequest(nonce: String, maximumFileSizeBytes: Long)(implicit
    rh: RequestHeader
  ) =
    UpscanInitiateRequest(
      callbackUrl = appConfig.baseInternalCallbackUrl + controller.callbackFromUpscan(currentJourneyId, nonce).url,
      successRedirect = Some(successRedirect),
      errorRedirect = Some(errorRedirect),
      minimumFileSize = Some(1),
      maximumFileSize = Some(maximumFileSizeBytes.toInt)
    )

  final def upscanRequestWhenUploadingMultipleFiles(
    nonce: String,
    maximumFileSizeBytes: Long
  )(implicit
    rh: RequestHeader
  ) =
    UpscanInitiateRequest(
      callbackUrl = appConfig.baseInternalCallbackUrl + controller.callbackFromUpscan(currentJourneyId, nonce).url,
      successRedirect = Some(successRedirectWhenUploadingMultipleFiles),
      errorRedirect = Some(errorRedirect),
      minimumFileSize = Some(1),
      maximumFileSize = Some(maximumFileSizeBytes.toInt)
    )

  // --------------------------------------------- //
  //                    ACTIONS                    //
  // --------------------------------------------- //

  // POST /initialize
  final val initialize: Action[AnyContent] =
    whenAuthenticatedInBackchannel
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
      .andCleanBreadcrumbs()

  // POST /wipe-out
  final val wipeOut: Action[AnyContent] =
    whenAuthenticatedInBackchannel
      .apply(Transitions.wipeOut)
      .displayUsing(renderWipeOutResponse)
      .andCleanBreadcrumbs()

  // GET /
  final val start: Action[AnyContent] =
    action { implicit request =>
      Future.successful(
        if (preferUploadMultipleFiles)
          Redirect(routes.FileUploadJourneyController.showChooseMultipleFiles)
        else
          Ok(views.startView(routes.FileUploadJourneyController.showChooseMultipleFiles))
      )
    }

  // GET /choose-files
  final val showChooseMultipleFiles: Action[AnyContent] =
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

  // GET /choose-file
  final val showChooseFile: Action[AnyContent] =
    whenAuthenticated
      .applyWithRequest { implicit request =>
        Transitions
          .initiateFileUpload(upscanRequest)(upscanInitiateConnector.initiate(_, _))
      }
      .redirectOrDisplayIf[State.UploadSingleFile]

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
      .waitForStateThenRedirect[State.Summary](INITIAL_CALLBACK_WAIT_TIME_SECONDS)
      .orApplyOnTimeout(Transitions.waitForFileVerification)
      .redirectOrDisplayIf[State.WaitingForFileVerification]

  // GET /journey/:journeyId/file-verification
  final def asyncWaitingForFileVerification(journeyId: String): Action[AnyContent] =
    actions
      .waitForStateAndDisplayUsing[State.Summary](
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

  // GET /summary
  final val showSummary: Action[AnyContent] =
    whenAuthenticated
      .show[State.Summary]
      .orApply(Transitions.backToSummary)

  // POST /summary
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

  // GET /preview/:reference/:fileName
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
        controller.showChooseMultipleFiles

      case _: State.UploadSingleFile =>
        controller.showChooseFile

      case _: State.WaitingForFileVerification =>
        controller.showWaitingForFileVerification

      case _: State.Summary =>
        controller.showSummary

      case _: State.SwitchToUploadSingleFile =>
        controller.showChooseFile

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
          Redirect(controller.showChooseMultipleFiles)
        else
          Redirect(controller.showChooseFile)

      case State.ContinueToHost(context, fileUploads) =>
        if (fileUploads.acceptedCount == 0)
          Redirect(context.config.getContinueWhenEmptyUrl)
        if (fileUploads.acceptedCount >= context.config.maximumNumberOfFiles)
          Redirect(context.config.getContinueWhenFullUrl)
        else
          Redirect(context.config.continueUrl)

      case State.UploadMultipleFiles(context, fileUploads) =>
        implicit val content = context.config.content
        Ok(
          views.uploadMultipleFilesView(
            minimumNumberOfFiles = context.config.minimumNumberOfFiles,
            maximumNumberOfFiles = context.config.maximumNumberOfFiles,
            initialNumberOfEmptyRows = context.config.initialNumberOfEmptyRows,
            maximumFileSizeBytes = context.config.maximumFileSizeBytes,
            filePickerAcceptFilter = context.config.getFilePickerAcceptFilter,
            allowedFileTypesHint = context.config.content.allowedFilesTypesHint
              .orElse(context.config.allowedFileExtensions)
              .getOrElse(context.config.allowedContentTypes),
            initialFileUploads = fileUploads.files,
            initiateNextFileUpload = controller.initiateNextFileUpload,
            checkFileVerificationStatus = controller.checkFileVerificationStatus,
            removeFile = controller.removeFileUploadByReferenceAsync,
            previewFile = controller.previewFileUploadByReference,
            markFileRejected = controller.markFileUploadAsRejectedAsync,
            continueAction = controller.continueToHost,
            backLink = backLinkFor(breadcrumbs)
          )
        )

      case State.UploadSingleFile(context, reference, uploadRequest, fileUploads, maybeUploadError) =>
        implicit val content = context.config.content
        Ok(
          views.uploadSingleFileView(
            maxFileUploadsNumber = context.config.maximumNumberOfFiles,
            maximumFileSizeBytes = context.config.maximumFileSizeBytes,
            filePickerAcceptFilter = context.config.getFilePickerAcceptFilter,
            allowedFileTypesHint = context.config.content.allowedFilesTypesHint
              .orElse(context.config.allowedFileExtensions)
              .getOrElse(context.config.allowedContentTypes),
            uploadRequest = uploadRequest,
            fileUploads = fileUploads,
            maybeUploadError = maybeUploadError,
            successAction = controller.showSummary,
            failureAction = controller.showChooseFile,
            checkStatusAction = controller.checkFileVerificationStatus(reference),
            backLink = backLinkFor(breadcrumbs)
          )
        )

      case State.WaitingForFileVerification(context, reference, _, _, _) =>
        implicit val content = context.config.content
        Ok(
          views.waitingForFileVerificationView(
            successAction = controller.showSummary,
            failureAction = controller.showChooseFile,
            checkStatusAction = controller.checkFileVerificationStatus(reference),
            backLink = backLinkFor(breadcrumbs)
          )
        )

      case State.Summary(context, fileUploads, _) =>
        implicit val content = context.config.content
        Ok(
          if (fileUploads.acceptedCount < context.config.maximumNumberOfFiles)
            views.summaryView(
              maxFileUploadsNumber = context.config.maximumNumberOfFiles,
              maximumFileSizeBytes = context.config.maximumFileSizeBytes,
              formWithErrors.or(UploadAnotherFileChoiceForm),
              fileUploads,
              controller.submitUploadAnotherFileChoice,
              controller.previewFileUploadByReference,
              controller.removeFileUploadByReference,
              backLinkFor(breadcrumbs)
            )
          else
            views.summaryNoChoiceView(
              context.config.maximumNumberOfFiles,
              fileUploads,
              controller.continueToHost,
              controller.previewFileUploadByReference,
              controller.removeFileUploadByReference,
              Call("GET", context.config.backlinkUrl)
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
                    uploadFileViewHelper,
                    controller.previewFileUploadByReference(_, _),
                    s.context.config.maximumFileSizeBytes.toInt,
                    s.context.config.content.allowedFilesTypesHint
                      .orElse(s.context.config.allowedFileExtensions)
                      .getOrElse(s.context.config.allowedContentTypes)
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
      case State.Initialized(context, _) =>
        Created.withHeaders(
          HeaderNames.LOCATION ->
            (
              if (!context.config.features.showUploadMultiple)
                routes.FileUploadJourneyController.showChooseFile
              else
                routes.FileUploadJourneyController.start
            ).url
        )
      case _ => BadRequest
    }

  private def renderWipeOutResponse =
    Renderer.simple { case _ => NoContent }

  private def renderFileRemovalStatus =
    Renderer.simple {
      case s: State => NoContent
      case _        => BadRequest
    }

  private def streamFileFromUspcan(
    reference: String
  ) =
    AsyncRenderer.simple {
      case s: HasFileUploads =>
        s.fileUploads.files.find(_.reference == reference) match {
          case Some(file: FileUpload.Accepted) =>
            getFileStream(
              file.url,
              file.fileName,
              file.fileMimeType,
              file.fileSize,
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
      case _: State.Summary                    => Created
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
