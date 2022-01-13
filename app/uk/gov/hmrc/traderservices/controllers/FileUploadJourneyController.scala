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

package uk.gov.hmrc.traderservices.controllers

import akka.actor.{ActorSystem, Scheduler}
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.Json
import play.api.mvc._
import play.api.{Configuration, Environment}
import play.mvc.Http.HeaderNames
import uk.gov.hmrc.traderservices.connectors._
import uk.gov.hmrc.traderservices.journeys.FileUploadJourneyModel.State._
import uk.gov.hmrc.traderservices.models._
import uk.gov.hmrc.traderservices.services.FileUploadJourneyServiceWithHeaderCarrier
import uk.gov.hmrc.traderservices.views.UploadFileViewContext
import uk.gov.hmrc.traderservices.wiring.AppConfig

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class FileUploadJourneyController @Inject() (
  amendCaseJourneyService: FileUploadJourneyServiceWithHeaderCarrier,
  views: uk.gov.hmrc.traderservices.views.AmendCaseViews,
  traderServicesApiConnector: TraderServicesApiConnector,
  upscanInitiateConnector: UpscanInitiateConnector,
  uploadFileViewContext: UploadFileViewContext,
  printStylesheet: ReceiptStylesheet,
  pdfGeneratorConnector: PdfGeneratorConnector,
  appConfig: AppConfig,
  authConnector: FrontendAuthConnector,
  environment: Environment,
  configuration: Configuration,
  controllerComponents: MessagesControllerComponents,
  val actorSystem: ActorSystem
) extends BaseJourneyController(
      amendCaseJourneyService,
      controllerComponents,
      appConfig,
      authConnector,
      environment,
      configuration
    ) with FileStream {

  final val controller = routes.FileUploadJourneyController

  import FileUploadJourneyController._
  import uk.gov.hmrc.traderservices.journeys.FileUploadJourneyModel._

  implicit val scheduler: Scheduler = actorSystem.scheduler

  // PUT /upload/initialize
  final val initialize: Action[AnyContent] =
    actions
      .parseJsonWithFallback[FileUploadInitializationRequest](BadRequest)
      .apply(Transitions.initialize)

  // GET /upload/finish
  final val finish: Action[AnyContent] =
    actions
      .show[ContinueToHost]
      .orApply(Transitions.finish)

  // ----------------------- FILES UPLOAD -----------------------

  /** Initial time to wait for callback arrival. */
  final val INITIAL_CALLBACK_WAIT_TIME_SECONDS = 2

  /** This cookie is set by the script on each request coming from one of our own pages open in the browser.
    */
  final val COOKIE_JSENABLED = "jsenabled"

  final def preferUploadMultipleFiles(implicit rh: RequestHeader): Boolean =
    rh.cookies.get(COOKIE_JSENABLED).isDefined && appConfig.uploadMultipleFilesFeature

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

  // GET /upload-files
  final val showUploadMultipleFiles: Action[AnyContent] =
    whenAuthorisedAsUser
      .apply(FileUploadTransitions.toUploadMultipleFiles)
      .redirectOrDisplayIf[FileUploadState.UploadMultipleFiles]

  // POST /upload-files/initialise/:uploadId
  final def initiateNextFileUpload(uploadId: String): Action[AnyContent] =
    whenAuthorisedAsUser
      .applyWithRequest { implicit request =>
        FileUploadTransitions
          .initiateNextFileUpload(uploadId)(upscanRequestWhenUploadingMultipleFiles)(
            upscanInitiateConnector.initiate(_)
          )
      }
      .displayUsing(renderUploadRequestJson(uploadId))

  // GET /file-upload
  final val showFileUpload: Action[AnyContent] =
    whenAuthorisedAsUser
      .applyWithRequest { implicit request =>
        FileUploadTransitions
          .initiateFileUpload(upscanRequest)(upscanInitiateConnector.initiate(_))
      }
      .redirectOrDisplayIf[FileUploadState.UploadFile]

  // GET /file-rejected
  final val markFileUploadAsRejected: Action[AnyContent] =
    whenAuthorisedAsUser
      .bindForm(UpscanUploadErrorForm)
      .apply(FileUploadTransitions.markUploadAsRejected)

  // POST /new/file-rejected
  final val markFileUploadAsRejectedAsync: Action[AnyContent] =
    whenAuthorisedAsUser
      .bindForm(UpscanUploadErrorForm)
      .apply(FileUploadTransitions.markUploadAsRejected)
      .displayUsing(acknowledgeFileUploadRedirect)

  // GET /journey/:journeyId/file-rejected
  final def asyncMarkFileUploadAsRejected(journeyId: String): Action[AnyContent] =
    actions
      .bindForm(UpscanUploadErrorForm)
      .apply(FileUploadTransitions.markUploadAsRejected)
      .displayUsing(acknowledgeFileUploadRedirect)

  // GET /file-verification
  final val showWaitingForFileVerification: Action[AnyContent] =
    whenAuthorisedAsUser
      .waitForStateThenRedirect[FileUploadState.FileUploaded](INITIAL_CALLBACK_WAIT_TIME_SECONDS)
      .orApplyOnTimeout(FileUploadTransitions.waitForFileVerification)
      .redirectOrDisplayIf[FileUploadState.WaitingForFileVerification]

  // GET /journey/:journeyId/file-verification
  final def asyncWaitingForFileVerification(journeyId: String): Action[AnyContent] =
    actions
      .waitForStateAndDisplayUsing[FileUploadState.FileUploaded](
        INITIAL_CALLBACK_WAIT_TIME_SECONDS,
        acknowledgeFileUploadRedirect
      )
      .orApplyOnTimeout(FileUploadTransitions.waitForFileVerification)
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
      .apply(FileUploadTransitions.markUploadAsPosted)
      .displayUsing(acknowledgeFileUploadRedirect)

  // POST /journey/:journeyId/callback-from-upscan/:nonce
  final def callbackFromUpscan(journeyId: String, nonce: String): Action[AnyContent] =
    actions
      .parseJsonWithFallback[UpscanNotification](BadRequest)
      .apply(FileUploadTransitions.upscanCallbackArrived(Nonce(nonce)))
      .transform {
        case r if r.header.status < 400 => NoContent
      }
      .recover { case e =>
        InternalServerError
      }

  // GET /file-uploaded
  final val showFileUploaded: Action[AnyContent] =
    whenAuthorisedAsUser
      .show[FileUploadState.FileUploaded]
      .orApply(FileUploadTransitions.backToFileUploaded)

  // POST /file-uploaded
  final val submitUploadAnotherFileChoice: Action[AnyContent] =
    whenAuthorisedAsUser
      .bindForm[Boolean](UploadAnotherFileChoiceForm)
      .applyWithRequest { implicit request =>
        FileUploadTransitions.submitedUploadAnotherFileChoice(upscanRequest)(upscanInitiateConnector.initiate(_))(
          Transitions.finish
        )
      }

  // GET /file-uploaded/:reference/remove
  final def removeFileUploadByReference(reference: String): Action[AnyContent] =
    whenAuthorisedAsUser
      .applyWithRequest { implicit request =>
        FileUploadTransitions.removeFileUploadByReference(reference)(upscanRequest)(
          upscanInitiateConnector.initiate(_)
        )
      }

  // POST /file-uploaded/:reference/remove
  final def removeFileUploadByReferenceAsync(reference: String): Action[AnyContent] =
    whenAuthorisedAsUser
      .applyWithRequest { implicit request =>
        FileUploadTransitions.removeFileUploadByReference(reference)(upscanRequest)(
          upscanInitiateConnector.initiate(_)
        )
      }
      .displayUsing(renderFileRemovalStatusJson(reference))

  // GET /file-uploaded/:reference/:fileName
  final def previewFileUploadByReference(reference: String, fileName: String): Action[AnyContent] =
    whenAuthorisedAsUser.showCurrentState
      .displayAsyncUsing(streamFileFromUspcan(reference))

  // GET /file-verification/:reference/status
  final def checkFileVerificationStatus(reference: String): Action[AnyContent] =
    whenAuthorisedAsUser.showCurrentState
      .displayUsing(renderFileVerificationStatus(reference))

  /** Function from the `State` to the `Call` (route), used by play-fsm internally to create redirects.
    */
  final override def getCallFor(state: State)(implicit request: Request[_]): Call =
    state match {
      case Initialized(config, fileUploads) =>
        if (preferUploadMultipleFiles) controller.showUploadMultipleFiles
        else controller.showFileUpload

      case ContinueToHost(config, fileUploads) =>
        Call("GET", config.continueUrl)

      case _: FileUploadState.UploadMultipleFiles =>
        controller.showUploadMultipleFiles

      case _: FileUploadState.UploadFile =>
        controller.showFileUpload

      case _: FileUploadState.WaitingForFileVerification =>
        controller.showWaitingForFileVerification

      case _: FileUploadState.FileUploaded =>
        controller.showFileUploaded

      case _ =>
        workInProgresDeadEndCall
    }

  import uk.gov.hmrc.play.fsm.OptionalFormOps._

  /** Function from the `State` to the `Result`, used by play-fsm internally to render the actual content.
    */
  final override def renderState(state: State, breadcrumbs: List[State], formWithErrors: Option[Form[_]])(implicit
    request: Request[_]
  ): Result =
    state match {
      case Uninitialized =>
        Redirect("https://www.gov.uk")

      case Initialized(config, fileUploads) =>
        if (preferUploadMultipleFiles)
          Redirect(controller.showUploadMultipleFiles)
        else
          Redirect(controller.showFileUpload)

      case ContinueToHost(config, fileUploads) =>
        Redirect(config.continueUrl)

      case FileUploadState.UploadMultipleFiles(model, fileUploads) =>
        Ok(
          views.uploadMultipleFilesView(
            maxFileUploadsNumber,
            fileUploads.files,
            initiateNextFileUpload = controller.initiateNextFileUpload,
            checkFileVerificationStatus = controller.checkFileVerificationStatus,
            removeFile = controller.removeFileUploadByReferenceAsync,
            previewFile = controller.previewFileUploadByReference,
            markFileRejected = controller.markFileUploadAsRejectedAsync,
            None,
            continueAction = controller.finish,
            backLink = backLinkFor(breadcrumbs)
          )
        )

      case FileUploadState.UploadFile(model, reference, uploadRequest, fileUploads, maybeUploadError) =>
        Ok(
          views.uploadFileView(
            uploadRequest,
            fileUploads,
            maybeUploadError,
            None,
            successAction = controller.showFileUploaded,
            failureAction = controller.showFileUpload,
            checkStatusAction = controller.checkFileVerificationStatus(reference),
            backLink = backLinkFor(breadcrumbs)
          )
        )

      case FileUploadState.WaitingForFileVerification(_, reference, _, _, _) =>
        Ok(
          views.waitingForFileVerificationView(
            successAction = controller.showFileUploaded,
            failureAction = controller.showFileUpload,
            checkStatusAction = controller.checkFileVerificationStatus(reference),
            backLink = backLinkFor(breadcrumbs)
          )
        )

      case FileUploadState.FileUploaded(model, fileUploads, _) =>
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
              controller.finish,
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
      case s: FileUploadState.UploadMultipleFiles =>
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

  private def renderFileRemovalStatusJson(
    reference: String
  ) =
    Renderer.simple {
      case s: FileUploadState => NoContent
      case _                  => BadRequest
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

  private lazy val acknowledgeFileUploadRedirect = Renderer.simple { case state =>
    (state match {
      case _: FileUploadState.UploadMultipleFiles        => Created
      case _: FileUploadState.FileUploaded               => Created
      case _: FileUploadState.WaitingForFileVerification => Accepted
      case _                                             => NoContent
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
