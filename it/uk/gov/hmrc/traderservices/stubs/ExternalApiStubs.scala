package uk.gov.hmrc.traderservices.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import play.api.libs.json.Json
import uk.gov.hmrc.traderservices.connectors.FileUploadResultPushConnector
import uk.gov.hmrc.traderservices.support.WireMockSupport

trait ExternalApiStubs {
  me: WireMockSupport =>

  def stubForFileDownload(status: Int, bytes: Array[Byte], fileName: String): String = {
    val url = s"$wireMockBaseUrlAsString/bucket/$fileName"

    stubFor(
      get(urlPathEqualTo(s"/bucket/$fileName"))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/octet-stream")
            .withHeader("Content-Length", s"${bytes.length}")
            .withBody(bytes)
        )
    )

    url
  }

  def stubForFileDownloadFailure(status: Int, fileName: String): String = {
    val url = s"$wireMockBaseUrlAsString/bucket/$fileName"

    stubFor(
      get(urlPathEqualTo(s"/bucket/$fileName"))
        .willReturn(
          aResponse()
            .withStatus(status)
        )
    )

    url
  }

  def givenSomePage(status: Int, path: String, content: String): Unit =
    stubFor(
      get(urlPathEqualTo(path))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "text/plain")
            .withBody(content)
        )
    )

  def givenHostPushEndpoint(path: String, request: FileUploadResultPushConnector.Request, status: Int): Unit =
    stubFor(
      post(urlPathEqualTo(path))
        .withRequestBody(equalToJson(Json.stringify(Json.toJson(request))))
        .willReturn(aResponse().withStatus(status))
    )

  def verifyHostPushEndpointHasHappened(path: String, times: Int = 1) {
    verify(times, postRequestedFor(urlEqualTo(path)))
  }

  def verifyHostPushEndpointHasNotHappened(path: String) {
    verify(0, postRequestedFor(urlEqualTo(path)))
  }

}
