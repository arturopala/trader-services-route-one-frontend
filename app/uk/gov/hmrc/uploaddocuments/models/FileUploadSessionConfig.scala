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

import FileUploadSessionConfig._
import play.api.libs.json.Json
import play.api.libs.functional.syntax._

final case class FileUploadSessionConfig(
  serviceId: String, // client ID used by upscan configuration
  nonce: Nonce, // unique secret shared by the host and upload microservices
  continueUrl: String, // url to continue after uploading the files
  backlinkUrl: String, // backlink url
  callbackUrl: String, // url where to post uploaded files
  cargo: Option[JsValue] = None, // opaque data carried through, from and to the host service,
  newFileDescription: Option[String] = None, // description of the new file added
  features: Features = Features(), // feature switches
  content: Content = Content() // page content customizations
)

object FileUploadSessionConfig {

  case class Features(
    javascriptDisabled: Boolean = false
  )

  case class Content(
    title: Option[String] = None,
    description: Option[String] = None
  )

  implicit val contentFormat: Format[Content] = Json.format[Content]

  implicit val featuresFormat: Format[Features] = Format(
    ((JsPath \ "javascriptDisabled").readWithDefault[Boolean](false)).map(Features.apply _),
    ((JsPath \ "javascriptDisabled").write[Boolean]).contramap(unlift(Features.unapply(_)))
  )

  implicit val format: Format[FileUploadSessionConfig] =
    Format(
      ((JsPath \ "serviceId").read[String]
        and (JsPath \ "nonce").read[Nonce]
        and (JsPath \ "continueUrl").read[String]
        and (JsPath \ "backlinkUrl").read[String]
        and (JsPath \ "callbackUrl").read[String]
        and (JsPath \ "cargo").readNullable[JsValue]
        and (JsPath \ "newFileDescription").readNullable[String]
        and (JsPath \ "features").readWithDefault[Features](Features())
        and (JsPath \ "content").readWithDefault[Content](Content()))(FileUploadSessionConfig.apply _),
      ((JsPath \ "serviceId").write[String]
        and (JsPath \ "nonce").write[Nonce]
        and (JsPath \ "continueUrl").write[String]
        and (JsPath \ "backlinkUrl").write[String]
        and (JsPath \ "callbackUrl").write[String]
        and (JsPath \ "cargo").writeNullable[JsValue]
        and (JsPath \ "newFileDescription").writeNullable[String]
        and (JsPath \ "features").write[Features]
        and (JsPath \ "content").write[Content])(unlift(FileUploadSessionConfig.unapply(_)))
    )
}
