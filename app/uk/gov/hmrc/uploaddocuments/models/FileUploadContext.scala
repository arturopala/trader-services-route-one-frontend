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

import play.api.libs.json.{Format, JsString, JsSuccess, Json, Reads, Writes}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HeaderNames
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import play.api.mvc.Headers
import play.api.mvc.RequestHeader

final case class FileUploadContext(
  config: FileUploadSessionConfig,
  callbackAuth: CallbackAuth = CallbackAuth.Any
)

sealed trait CallbackAuth {
  def populate(hc: HeaderCarrier): HeaderCarrier
}

object CallbackAuth {

  final case class HostHeaders(
    headers: Seq[(String, String)]
  ) extends CallbackAuth {
    def populate(hc: HeaderCarrier): HeaderCarrier =
      HeaderCarrierConverter.fromHeadersAndSession(Headers(headers: _*), None)

    final override def equals(obj: scala.Any): Boolean =
      if (obj.isInstanceOf[Any]) true
      else if (obj.isInstanceOf[HostHeaders])
        obj.asInstanceOf[HostHeaders].headers.equals(this.headers)
      else false

    override def hashCode(): Int = 0
    override def toString(): String =
      s"CallbackAuth.HostHeaders(${headers.map(h => s"${h._1}=${h._2}").mkString(", ")})"
  }

  object HostHeaders {
    implicit val reads: Reads[HostHeaders] = Json.reads[HostHeaders]
    implicit val writes: Writes[HostHeaders] = Json.writes[HostHeaders]
  }

  object Any extends CallbackAuth {
    override def populate(hc: HeaderCarrier): HeaderCarrier = hc
    override def equals(obj: Any): Boolean = if (obj.isInstanceOf[CallbackAuth]) true else false
    override def hashCode(): Int = 0
    override def toString(): String = "CallbackAuth.Any"
  }

  def from(rh: RequestHeader): Option[CallbackAuth] = {
    val headers = HeaderCarrierConverter.fromRequest(rh).headers(HeaderNames.explicitlyIncludedHeaders)
    Some(HostHeaders(headers))
  }

  implicit val format: Format[CallbackAuth] =
    Format(
      Reads(value => HostHeaders.reads.reads(value).orElse(JsSuccess(Any))),
      Writes.apply {
        case value: HostHeaders => HostHeaders.writes.writes(value)
        case _                  => JsString("Any")
      }
    )
}

object FileUploadContext {
  implicit val format: Format[FileUploadContext] = Json.format[FileUploadContext]
}
