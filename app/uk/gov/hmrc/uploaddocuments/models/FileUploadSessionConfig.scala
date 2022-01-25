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

package uk.gov.hmrc.uploaddocuments.models

import play.api.libs.json.{Format, JsPath, JsValue}

final case class FileUploadSessionConfig(
  serviceId: String, // client ID used by upscan configuration
  nonce: Nonce, // unique secret shared by the host and upload microservices
  continueUrl: String, // url to continue after uploading the files
  backlinkUrl: String, // backlink url
  callbackUrl: String, // url where to post uploaded files
  cargo: Option[JsValue] = None, // opaque data carried through, from and to the host service
  javascriptDisabled: Boolean = false
)

object FileUploadSessionConfig {
  import play.api.libs.functional.syntax._

  implicit val format: Format[FileUploadSessionConfig] =
    Format(
      ((JsPath \ "serviceId").read[String]
        and (JsPath \ "nonce").read[Nonce]
        and (JsPath \ "continueUrl").read[String]
        and (JsPath \ "backlinkUrl").read[String]
        and (JsPath \ "callbackUrl").read[String]
        and (JsPath \ "cargo").readNullable[JsValue]
        and (JsPath \ "javascriptDisabled").readWithDefault[Boolean](false))(FileUploadSessionConfig.apply _),
      ((JsPath \ "serviceId").write[String]
        and (JsPath \ "nonce").write[Nonce]
        and (JsPath \ "continueUrl").write[String]
        and (JsPath \ "backlinkUrl").write[String]
        and (JsPath \ "callbackUrl").write[String]
        and (JsPath \ "cargo").writeNullable[JsValue]
        and (JsPath \ "javascriptDisabled").write[Boolean])(unlift(FileUploadSessionConfig.unapply(_)))
    )
}
