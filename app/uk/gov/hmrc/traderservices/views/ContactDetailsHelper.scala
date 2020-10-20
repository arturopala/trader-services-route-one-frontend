/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.traderservices.views

import uk.gov.hmrc.traderservices.models.ImportContactInfo
import uk.gov.hmrc.govukfrontend.views.viewmodels.summarylist.SummaryList
import play.api.i18n.Messages
import play.api.mvc.Call

trait ContactDetailsHelper extends SummaryListRowHelper {

  def summaryListOfContactDetails(contactDetails: ImportContactInfo, changeCall: Call)(implicit
    messages: Messages
  ): SummaryList = {

    val contactEmail = contactDetails.contactEmail.map(value => s"<div>$value</div>").getOrElse("")
    val contactNumber = contactDetails.contactNumber.map(value => s"<div>$value</div>").getOrElse("")

    SummaryList(
      Seq(
        summaryListRow(
          label = "summary.contact-details",
          value = contactEmail + contactNumber,
          visuallyHiddenText = Some("summary.contact-details"),
          action = (changeCall, "site.change")
        )
      )
    )
  }

}