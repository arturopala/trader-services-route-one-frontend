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

package uk.gov.hmrc.uploaddocuments.journeys

import uk.gov.hmrc.uploaddocuments.models._
import uk.gov.hmrc.play.fsm.JourneyModel
import uk.gov.hmrc.uploaddocuments.connectors.UpscanInitiateRequest
import scala.concurrent.Future
import uk.gov.hmrc.uploaddocuments.connectors.FileUploadResultPushConnector
import uk.gov.hmrc.uploaddocuments.connectors.UpscanInitiateResponse
import scala.concurrent.ExecutionContext

object FileUploadJourneyModel extends JourneyModel {

  sealed trait State
  sealed trait IsTransient
  sealed trait IsError

  override val root: State = State.Uninitialized

  final val maxFileUploadsNumber: Int = 10

  /** Minimum time gap to allow overwriting upload status. */
  final val minStatusOverwriteGapInMilliseconds: Long = 1000

  /** Marker trait of permitted entry states. */
  trait CanEnterFileUpload extends State {
    def config: FileUploadSessionConfig
    def fileUploadsOpt: Option[FileUploads]
  }

  sealed trait FileUploadState extends State {
    def config: FileUploadSessionConfig
    def fileUploads: FileUploads
  }

  object State {

    /** Root state of the journey. */
    final case object Uninitialized extends State

    final case class Initialized(config: FileUploadSessionConfig, fileUploads: FileUploads)
        extends State with CanEnterFileUpload {
      final def fileUploadsOpt: Option[FileUploads] =
        if (fileUploads.isEmpty) None else Some(fileUploads)
    }

    final case class ContinueToHost(config: FileUploadSessionConfig, fileUploads: FileUploads)
        extends State with CanEnterFileUpload {
      final def fileUploadsOpt: Option[FileUploads] =
        if (fileUploads.isEmpty) None else Some(fileUploads)
    }

    final case class UploadFile(
      config: FileUploadSessionConfig,
      reference: String,
      uploadRequest: UploadRequest,
      fileUploads: FileUploads,
      maybeUploadError: Option[FileUploadError] = None
    ) extends FileUploadState

    final case class WaitingForFileVerification(
      config: FileUploadSessionConfig,
      reference: String,
      uploadRequest: UploadRequest,
      currentFileUpload: FileUpload,
      fileUploads: FileUploads
    ) extends FileUploadState with IsTransient

    final case class FileUploaded(
      config: FileUploadSessionConfig,
      fileUploads: FileUploads,
      acknowledged: Boolean = false
    ) extends FileUploadState

    final case class UploadMultipleFiles(
      config: FileUploadSessionConfig,
      fileUploads: FileUploads
    ) extends FileUploadState

  }

  type UpscanInitiateApi = (String, UpscanInitiateRequest) => Future[UpscanInitiateResponse]
  type FileUploadResultPushApi =
    FileUploadResultPushConnector.Request => Future[FileUploadResultPushConnector.Response]

  /** Common file upload initialization helper. */
  private[journeys] final def gotoFileUploadOrUploaded(
    config: FileUploadSessionConfig,
    upscanRequest: String => UpscanInitiateRequest,
    upscanInitiate: UpscanInitiateApi,
    fileUploadsOpt: Option[FileUploads],
    showUploadSummaryIfAny: Boolean
  )(implicit ec: ExecutionContext): Future[State] = {
    val fileUploads = fileUploadsOpt.getOrElse(FileUploads())
    if ((showUploadSummaryIfAny && fileUploads.nonEmpty) || fileUploads.acceptedCount >= maxFileUploadsNumber)
      goto(
        State.FileUploaded(config, fileUploads)
      )
    else {
      val nonce = Nonce.random
      for {
        upscanResponse <- upscanInitiate(config.serviceId, upscanRequest(nonce.toString()))
      } yield State.UploadFile(
        config,
        upscanResponse.reference,
        upscanResponse.uploadRequest,
        fileUploads + FileUpload.Initiated(nonce, Timestamp.now, upscanResponse.reference, None, None)
      )
    }
  }

  object Transitions {
    import State._

    final def initialize(request: FileUploadInitializationRequest) =
      Transition { case _ =>
        goto(Initialized(request.config, request.toFileUploads))
      }

    final val continueToHost =
      Transition {
        case s: FileUploadState                  => goto(ContinueToHost(s.config, s.fileUploads))
        case Initialized(config, fileUploads)    => goto(ContinueToHost(config, fileUploads))
        case ContinueToHost(config, fileUploads) => goto(ContinueToHost(config, fileUploads))
      }

    private def resetFileUploadStatusToInitiated(reference: String, fileUploads: FileUploads): FileUploads =
      fileUploads.copy(files = fileUploads.files.map {
        case f if f.reference == reference =>
          FileUpload.Initiated(f.nonce, Timestamp.now, f.reference, None, None)
        case other => other
      })

    final val toUploadMultipleFiles =
      Transition {
        case current: UploadMultipleFiles =>
          goto(current.copy(fileUploads = current.fileUploads.onlyAccepted))

        case state: CanEnterFileUpload =>
          goto(
            UploadMultipleFiles(
              config = state.config,
              fileUploads = state.fileUploadsOpt.map(_.onlyAccepted).getOrElse(FileUploads())
            )
          )

        case state: FileUploadState =>
          goto(UploadMultipleFiles(state.config, state.fileUploads.onlyAccepted))

      }

    final def initiateNextFileUpload(uploadId: String)(
      upscanRequest: String => UpscanInitiateRequest
    )(upscanInitiate: UpscanInitiateApi)(implicit ec: ExecutionContext) =
      Transition { case state: UploadMultipleFiles =>
        if (
          !state.fileUploads.hasUploadId(uploadId) &&
          state.fileUploads.initiatedOrAcceptedCount < maxFileUploadsNumber
        ) {
          val nonce = Nonce.random
          upscanInitiate(state.config.serviceId, upscanRequest(nonce.toString()))
            .flatMap { upscanResponse =>
              goto(
                state.copy(fileUploads =
                  state.fileUploads + FileUpload
                    .Initiated(
                      nonce,
                      Timestamp.now,
                      upscanResponse.reference,
                      Some(upscanResponse.uploadRequest),
                      Some(uploadId)
                    )
                )
              )
            }
        } else goto(state)
      }

    final def initiateFileUpload(
      upscanRequest: String => UpscanInitiateRequest
    )(upscanInitiate: UpscanInitiateApi)(implicit ec: ExecutionContext) =
      Transition {
        case state: CanEnterFileUpload =>
          gotoFileUploadOrUploaded(
            state.config,
            upscanRequest,
            upscanInitiate,
            state.fileUploadsOpt,
            showUploadSummaryIfAny = true
          )

        case current @ UploadFile(config, reference, uploadRequest, fileUploads, maybeUploadError) =>
          if (maybeUploadError.isDefined)
            goto(
              current
                .copy(fileUploads = resetFileUploadStatusToInitiated(reference, fileUploads))
            )
          else
            goto(current)

        case WaitingForFileVerification(
              config,
              reference,
              uploadRequest,
              currentFileUpload,
              fileUploads
            ) =>
          goto(UploadFile(config, reference, uploadRequest, fileUploads))

        case current @ FileUploaded(config, fileUploads, _) =>
          if (fileUploads.acceptedCount >= maxFileUploadsNumber)
            goto(current)
          else
            gotoFileUploadOrUploaded(
              config,
              upscanRequest,
              upscanInitiate,
              Some(fileUploads),
              showUploadSummaryIfAny = false
            )

        case UploadMultipleFiles(config, fileUploads) =>
          gotoFileUploadOrUploaded(
            config,
            upscanRequest,
            upscanInitiate,
            Some(fileUploads),
            showUploadSummaryIfAny = false
          )

      }

    final def markUploadAsRejected(error: S3UploadError) =
      Transition {
        case current @ UploadFile(
              config,
              reference,
              uploadRequest,
              fileUploads,
              maybeUploadError
            ) =>
          val now = Timestamp.now
          val updatedFileUploads = fileUploads.copy(files = fileUploads.files.map {
            case fu @ FileUpload.Initiated(nonce, _, ref, _, _)
                if ref == error.key && canOverwriteFileUploadStatus(fu, true, now) =>
              FileUpload.Rejected(nonce, Timestamp.now, ref, error)
            case u => u
          })
          goto(current.copy(fileUploads = updatedFileUploads, maybeUploadError = Some(FileTransmissionFailed(error))))

        case current @ UploadMultipleFiles(config, fileUploads) =>
          val updatedFileUploads = fileUploads.copy(files = fileUploads.files.map {
            case FileUpload(nonce, ref, _) if ref == error.key =>
              FileUpload.Rejected(nonce, Timestamp.now, ref, error)
            case u => u
          })
          goto(current.copy(fileUploads = updatedFileUploads))
      }

    final def markUploadAsPosted(receipt: S3UploadSuccess) =
      Transition { case current @ UploadMultipleFiles(config, fileUploads) =>
        val now = Timestamp.now
        val updatedFileUploads =
          fileUploads.copy(files = fileUploads.files.map {
            case fu @ FileUpload(nonce, ref, _) if ref == receipt.key && canOverwriteFileUploadStatus(fu, true, now) =>
              FileUpload.Posted(nonce, Timestamp.now, ref)
            case u => u
          })
        goto(current.copy(fileUploads = updatedFileUploads))
      }

    /** Common transition helper based on the file upload status. */
    final def commonFileUploadStatusHandler(
      config: FileUploadSessionConfig,
      fileUploads: FileUploads,
      reference: String,
      uploadRequest: UploadRequest,
      fallbackState: => State
    ): PartialFunction[Option[FileUpload], Future[State]] = {

      case None =>
        goto(fallbackState)

      case Some(initiatedFile: FileUpload.Initiated) =>
        goto(UploadFile(config, reference, uploadRequest, fileUploads))

      case Some(postedFile: FileUpload.Posted) =>
        goto(
          WaitingForFileVerification(
            config,
            reference,
            uploadRequest,
            postedFile,
            fileUploads
          )
        )

      case Some(acceptedFile: FileUpload.Accepted) =>
        goto(FileUploaded(config, fileUploads))

      case Some(failedFile: FileUpload.Failed) =>
        goto(
          UploadFile(
            config,
            reference,
            uploadRequest,
            fileUploads,
            Some(FileVerificationFailed(failedFile.details))
          )
        )

      case Some(rejectedFile: FileUpload.Rejected) =>
        goto(
          UploadFile(
            config,
            reference,
            uploadRequest,
            fileUploads,
            Some(FileTransmissionFailed(rejectedFile.details))
          )
        )

      case Some(duplicatedFile: FileUpload.Duplicate) =>
        goto(
          UploadFile(
            config,
            reference,
            uploadRequest,
            fileUploads,
            Some(
              DuplicateFileUpload(
                duplicatedFile.checksum,
                duplicatedFile.existingFileName,
                duplicatedFile.duplicateFileName
              )
            )
          )
        )
    }

    /** Transition when file has been uploaded and should wait for verification. */
    final val waitForFileVerification =
      Transition {
        /** Change file status to posted and wait. */
        case current @ UploadFile(
              config,
              reference,
              uploadRequest,
              fileUploads,
              errorOpt
            ) =>
          val updatedFileUploads = fileUploads.copy(files = fileUploads.files.map {
            case FileUpload.Initiated(nonce, _, ref, _, _) if ref == reference =>
              FileUpload.Posted(nonce, Timestamp.now, reference)
            case other => other
          })
          val currentUpload = updatedFileUploads.files.find(_.reference == reference)
          commonFileUploadStatusHandler(
            config,
            updatedFileUploads,
            reference,
            uploadRequest,
            current.copy(fileUploads = updatedFileUploads)
          )
            .apply(currentUpload)

        /** If waiting already, keep waiting. */
        case current @ WaitingForFileVerification(
              config,
              reference,
              uploadRequest,
              currentFileUpload,
              fileUploads
            ) =>
          val currentUpload = fileUploads.files.find(_.reference == reference)
          commonFileUploadStatusHandler(
            config,
            fileUploads,
            reference,
            uploadRequest,
            UploadFile(config, reference, uploadRequest, fileUploads)
          )
            .apply(currentUpload)

        /** If file already uploaded, do nothing. */
        case state: FileUploaded =>
          goto(state.copy(acknowledged = true))
      }

    final def canOverwriteFileUploadStatus(
      fileUpload: FileUpload,
      allowStatusOverwrite: Boolean,
      now: Timestamp
    ): Boolean =
      fileUpload.isNotReady ||
        (allowStatusOverwrite && now.isAfter(fileUpload.timestamp, minStatusOverwriteGapInMilliseconds))

    /** Transition when async notification arrives from the Upscan. */
    final def upscanCallbackArrived(
      pushfileUploadResult: FileUploadResultPushApi
    )(requestNonce: Nonce)(notification: UpscanNotification) = {
      val now = Timestamp.now

      def updateFileUploads(fileUploads: FileUploads, allowStatusOverwrite: Boolean): (FileUploads, Boolean) = {
        val modifiedFileUploads = fileUploads.copy(files = fileUploads.files.map {
          // update status of the file with matching nonce
          case fileUpload @ FileUpload(nonce, reference, _)
              if nonce.value == requestNonce.value && canOverwriteFileUploadStatus(
                fileUpload,
                allowStatusOverwrite,
                now
              ) =>
            notification match {
              case UpscanFileReady(_, url, uploadDetails) =>
                // check for existing file uploads with duplicated checksum
                val modifiedFileUpload: FileUpload = fileUploads.files
                  .find(file =>
                    file.checksumOpt.contains(uploadDetails.checksum) && file.reference != notification.reference
                  ) match {
                  case Some(existingFileUpload: FileUpload.Accepted) =>
                    FileUpload.Duplicate(
                      nonce,
                      Timestamp.now,
                      reference,
                      uploadDetails.checksum,
                      existingFileName = existingFileUpload.fileName,
                      duplicateFileName = uploadDetails.fileName
                    )
                  case _ =>
                    FileUpload.Accepted(
                      nonce,
                      Timestamp.now,
                      reference,
                      url,
                      uploadDetails.uploadTimestamp,
                      uploadDetails.checksum,
                      FileUpload.sanitizeFileName(uploadDetails.fileName),
                      uploadDetails.fileMimeType,
                      uploadDetails.size
                    )
                }
                modifiedFileUpload

              case UpscanFileFailed(_, failureDetails) =>
                FileUpload.Failed(
                  nonce,
                  Timestamp.now,
                  reference,
                  failureDetails
                )
            }
          case u => u
        })
        (modifiedFileUploads, modifiedFileUploads.acceptedCount != fileUploads.acceptedCount)
      }

      Transition {
        case current @ WaitingForFileVerification(
              config,
              reference,
              uploadRequest,
              currentFileUpload,
              fileUploads
            ) =>
          val (updatedFileUploads, newlyAccepted) = updateFileUploads(fileUploads, allowStatusOverwrite = false)
          if (newlyAccepted)
            pushfileUploadResult(FileUploadResultPushConnector.Request.from(current.config, updatedFileUploads))
          val currentUpload = updatedFileUploads.files.find(_.reference == reference)
          commonFileUploadStatusHandler(
            config,
            updatedFileUploads,
            reference,
            uploadRequest,
            current.copy(fileUploads = updatedFileUploads)
          )
            .apply(currentUpload)

        case current @ UploadFile(config, reference, uploadRequest, fileUploads, errorOpt) =>
          val (updatedFileUploads, newlyAccepted) = updateFileUploads(fileUploads, allowStatusOverwrite = false)
          if (newlyAccepted)
            pushfileUploadResult(FileUploadResultPushConnector.Request.from(config, updatedFileUploads))
          val currentUpload = updatedFileUploads.files.find(_.reference == reference)
          commonFileUploadStatusHandler(
            config,
            updatedFileUploads,
            reference,
            uploadRequest,
            current.copy(fileUploads = updatedFileUploads)
          )
            .apply(currentUpload)

        case current @ UploadMultipleFiles(config, fileUploads) =>
          val (updatedFileUploads, newlyAccepted) = updateFileUploads(fileUploads, allowStatusOverwrite = true)
          if (newlyAccepted)
            pushfileUploadResult(FileUploadResultPushConnector.Request.from(config, updatedFileUploads))
          goto(current.copy(fileUploads = updatedFileUploads))

        case current @ ContinueToHost(config, fileUploads) =>
          val (updatedFileUploads, newlyAccepted) = updateFileUploads(fileUploads, allowStatusOverwrite = true)
          if (newlyAccepted)
            pushfileUploadResult(FileUploadResultPushConnector.Request.from(config, updatedFileUploads))
          goto(current.copy(fileUploads = updatedFileUploads))
      }
    }

    final def submitedUploadAnotherFileChoice(
      upscanRequest: String => UpscanInitiateRequest
    )(
      upscanInitiate: UpscanInitiateApi
    )(exitFileUpload: Transition)(uploadAnotherFile: Boolean)(implicit ec: ExecutionContext) =
      Transition { case current @ FileUploaded(config, fileUploads, acknowledged) =>
        if (uploadAnotherFile && fileUploads.acceptedCount < maxFileUploadsNumber)
          gotoFileUploadOrUploaded(
            config,
            upscanRequest,
            upscanInitiate,
            Some(fileUploads),
            showUploadSummaryIfAny = false
          )
        else
          exitFileUpload.apply(current)
      }

    final def removeFileUploadByReference(reference: String)(
      upscanRequest: String => UpscanInitiateRequest
    )(upscanInitiate: UpscanInitiateApi)(
      pushfileUploadResult: FileUploadResultPushApi
    )(implicit ec: ExecutionContext) =
      Transition {
        case current: FileUploaded =>
          val updatedFileUploads = current.fileUploads
            .copy(files = current.fileUploads.files.filterNot(_.reference == reference))
          if (updatedFileUploads.acceptedCount != current.fileUploads.acceptedCount) {
            pushfileUploadResult(FileUploadResultPushConnector.Request.from(current.config, updatedFileUploads))
          }
          val updatedCurrentState = current.copy(fileUploads = updatedFileUploads)
          if (updatedFileUploads.isEmpty)
            initiateFileUpload(upscanRequest)(upscanInitiate)
              .apply(updatedCurrentState)
          else
            goto(updatedCurrentState)

        case current: UploadMultipleFiles =>
          val updatedFileUploads = current.fileUploads
            .copy(files = current.fileUploads.files.filterNot(_.reference == reference))
          if (updatedFileUploads.acceptedCount != current.fileUploads.acceptedCount) {
            pushfileUploadResult(FileUploadResultPushConnector.Request.from(current.config, updatedFileUploads))
          }
          val updatedCurrentState = current.copy(fileUploads = updatedFileUploads)
          goto(updatedCurrentState)
      }

    final val backToFileUploaded =
      Transition {
        case s: FileUploadState =>
          if (s.fileUploads.nonEmpty)
            goto(FileUploaded(s.config, s.fileUploads, acknowledged = true))
          else
            Transitions.continueToHost.apply(s)

        case s: CanEnterFileUpload =>
          if (s.fileUploadsOpt.exists(_.nonEmpty))
            goto(FileUploaded(s.config, s.fileUploadsOpt.get, acknowledged = true))
          else
            Transitions.continueToHost.apply(s)

        case s =>
          Transitions.continueToHost.apply(s)
      }
  }

}
