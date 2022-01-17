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

package uk.gov.hmrc.traderservices.journeys

import uk.gov.hmrc.traderservices.models._

object FileUploadJourneyModel extends FileUploadJourneyModelMixin {

  sealed trait IsError

  override val root: State = State.Uninitialized

  final override val maxFileUploadsNumber: Int = 10

  final override val retreatFromFileUpload: Transition = Transitions.continueToHost

  /** Opaque data carried through the file upload process. */
  final type FileUploadHostData = FileUploadSessionConfig

  /** All the possible states the journey can take. */
  object State {

    /** Root state of the journey. */
    final case object Uninitialized extends State

    final case class Initialized(config: FileUploadSessionConfig, fileUploads: FileUploads)
        extends State with CanEnterFileUpload {
      final def hostData: FileUploadHostData = config
      final def fileUploadsOpt: Option[FileUploads] =
        if (fileUploads.isEmpty) None else Some(fileUploads)
    }

    final case class ContinueToHost(config: FileUploadSessionConfig, fileUploads: FileUploads)
        extends State with CanEnterFileUpload {
      final def hostData: FileUploadHostData = config
      final def fileUploadsOpt: Option[FileUploads] =
        if (fileUploads.isEmpty) None else Some(fileUploads)
    }
  }

  /** This is where things happen a.k.a bussiness logic of the service. */
  object Transitions {

    import State._

    final def initialize(request: FileUploadInitializationRequest) =
      Transition { case _ =>
        goto(Initialized(request.config, request.toFileUploads))
      }

    final val continueToHost =
      Transition {
        case s: FileUploadState                  => goto(ContinueToHost(s.hostData, s.fileUploads))
        case Initialized(config, fileUploads)    => goto(ContinueToHost(config, fileUploads))
        case ContinueToHost(config, fileUploads) => goto(ContinueToHost(config, fileUploads))
      }
  }

}
