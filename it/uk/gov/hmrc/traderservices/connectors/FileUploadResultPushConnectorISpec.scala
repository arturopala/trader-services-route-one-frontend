package uk.gov.hmrc.traderservices.connectors

import play.api.Application
import uk.gov.hmrc.http._
import uk.gov.hmrc.traderservices.models._
import uk.gov.hmrc.traderservices.stubs.ExternalApiStubs
import uk.gov.hmrc.traderservices.support.AppISpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.util.Success
import java.time.ZonedDateTime

class FileUploadResultPushConnectorISpec extends FileUploadResultPushConnectorISpecSetup {

  override implicit val defaultTimeout: FiniteDuration = 10 seconds

  import FileUploadResultPushConnector._

  "FileUploadResultPushConnector" when {
    "push" should {
      "retry when applicable" in {
        FileUploadResultPushConnector.shouldRetry(Success(Right(()))) shouldBe false
        FileUploadResultPushConnector.shouldRetry(Success(Left(Error(501, "")))) shouldBe true
        FileUploadResultPushConnector.shouldRetry(Success(Left(Error(500, "")))) shouldBe true
        FileUploadResultPushConnector.shouldRetry(Success(Left(Error(499, "")))) shouldBe true
        FileUploadResultPushConnector.shouldRetry(Success(Left(Error(498, "")))) shouldBe false
        FileUploadResultPushConnector.shouldRetry(Success(Left(Error(404, "")))) shouldBe false
        FileUploadResultPushConnector.shouldRetry(Success(Left(Error(403, "")))) shouldBe false
        FileUploadResultPushConnector.shouldRetry(Success(Left(Error(400, "")))) shouldBe false
      }

      def request(url: String): Request =
        Request(
          "dummy-host",
          url,
          Nonce(123),
          Seq(
            UploadedFile(
              upscanReference = "jjSJKjksjJSJ",
              downloadUrl = "https://aws.amzon.com/dummy.jpg",
              uploadTimestamp = ZonedDateTime.parse("2007-12-03T10:15:30+01:00"),
              checksum = "akskakslaklskalkskalksl",
              fileName = "dummy.jpg",
              fileMimeType = "image/jpg",
              fileSize = Some(1024)
            )
          )
        )

      "accept valid request and return success when response 204" in {
        val path = s"/dummy-host-endpoint"
        val url = s"$wireMockBaseUrlAsString$path"
        givenHostPushEndpoint(path, request(url), 204)
        val result: Response = await(connector.push(request(url)))
        result.isRight shouldBe true
        verifyHostPushEndpointHasHappened(path, 1)
      }

      "accept valid request and return an error without retrying" in {
        (200 to 498).filterNot(Set(204, 301, 302, 303, 307, 308).contains).foreach { status =>
          val path = s"/dummy-host-endpoint-$status"
          val url = s"$wireMockBaseUrlAsString$path"
          givenHostPushEndpoint(path, request(url), status)
          val result: Response = await(connector.push(request(url)))
          result shouldBe Left(Error(status, s"Failure to push to $url: "))
          verifyHostPushEndpointHasHappened(path, 1)
        }
      }

      "accept valid request and return an error without retrying if 3xx" in {
        Set(301, 302, 303, 307, 308).foreach { status =>
          val path = s"/dummy-host-endpoint-$status"
          val url = s"$wireMockBaseUrlAsString$path"
          givenHostPushEndpoint(path, request(url), status)
          val result: Response = await(connector.push(request(url)))
          result shouldBe Left(Error(0, "originalUrl"))
          verifyHostPushEndpointHasHappened(path, 1)
        }
      }

      "accept valid request and return an error after retrying" in {
        (499 to 599).foreach { status =>
          val path = s"/dummy-host-endpoint-$status"
          val url = s"$wireMockBaseUrlAsString$path"
          givenHostPushEndpoint(path, request(url), status)
          val result: Response = await(connector.push(request(url)))
          result shouldBe Left(Error(status, s"Failure to push to $url: "))
          verifyHostPushEndpointHasHappened(path, 3)
        }
      }
    }
  }

}

trait FileUploadResultPushConnectorISpecSetup extends AppISpec with ExternalApiStubs {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  override def fakeApplication: Application = appBuilder.build()

  lazy val connector: FileUploadResultPushConnector =
    app.injector.instanceOf[FileUploadResultPushConnector]

}
