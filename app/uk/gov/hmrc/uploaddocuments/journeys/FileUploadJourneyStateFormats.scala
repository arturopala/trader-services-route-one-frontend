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

import play.api.libs.json._
import uk.gov.hmrc.play.fsm.JsonStateFormats
import uk.gov.hmrc.uploaddocuments.journeys.FileUploadJourneyModel.State._
import uk.gov.hmrc.uploaddocuments.journeys.FileUploadJourneyModel.State

object FileUploadJourneyStateFormats extends JsonStateFormats[State] {

  val initializedFormat = Json.format[Initialized]
  val continueToHostFormat = Json.format[ContinueToHost]
  val uploadFileFormat = Json.format[UploadFile]
  val fileUploadedFormat = Json.format[FileUploaded]
  val waitingForFileVerificationFormat = Json.format[WaitingForFileVerification]
  val uploadMultipleFilesFormat = Json.format[UploadMultipleFiles]

  override val serializeStateProperties: PartialFunction[State, JsValue] = {
    case s: Initialized                => initializedFormat.writes(s)
    case s: ContinueToHost             => continueToHostFormat.writes(s)
    case s: UploadFile                 => uploadFileFormat.writes(s)
    case s: FileUploaded               => fileUploadedFormat.writes(s)
    case s: WaitingForFileVerification => waitingForFileVerificationFormat.writes(s)
    case s: UploadMultipleFiles        => uploadMultipleFilesFormat.writes(s)
  }

  override def deserializeState(stateName: String, properties: JsValue): JsResult[State] =
    stateName match {
      case "Uninitialized"              => JsSuccess(Uninitialized)
      case "Initialized"                => initializedFormat.reads(properties)
      case "ContinueToHost"             => continueToHostFormat.reads(properties)
      case "UploadFile"                 => uploadFileFormat.reads(properties)
      case "FileUploaded"               => fileUploadedFormat.reads(properties)
      case "WaitingForFileVerification" => waitingForFileVerificationFormat.reads(properties)
      case "UploadMultipleFiles"        => uploadMultipleFilesFormat.reads(properties)
      case _                            => JsError(s"Unknown state name $stateName")
    }
}
