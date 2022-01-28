package uk.gov.hmrc.uploaddocuments.support

import uk.gov.hmrc.uploaddocuments.wiring.AppConfig
import scala.concurrent.duration.Duration
import scala.concurrent.duration._

case class TestAppConfig(
  wireMockBaseUrl: String,
  wireMockPort: Int
) extends AppConfig {

  override val appName: String = "upload-documents"
  override val baseInternalCallbackUrl: String = wireMockBaseUrl
  override val baseExternalCallbackUrl: String = wireMockBaseUrl
  override val authBaseUrl: String = wireMockBaseUrl
  override val upscanInitiateBaseUrl: String = wireMockBaseUrl
  override val mongoSessionExpiration: Duration = 1.hour
  override val contactHost: String = wireMockBaseUrl
  override val contactFormServiceIdentifier: String = "dummy"
  override val signOutUrl: String = wireMockBaseUrl + "/dummy-sign-out-url"
  override val authorisedServiceName: String = "HMRC-XYZ"
  override val authorisedIdentifierKey: String = "EORINumber"
  override val timeout: Int = 10
  override val countdown: Int = 2
  override val govukStartUrl: String = wireMockBaseUrl + "/dummy-start-url"

  override val fileUploadResultPushRetryIntervals: Seq[FiniteDuration] =
    Seq(FiniteDuration(10, "ms"), FiniteDuration(20, "ms"))

}
