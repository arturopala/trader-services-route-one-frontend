package uk.gov.hmrc.uploaddocuments.controllers

import akka.actor.ActorSystem
import play.api.http.{HeaderNames, MimeTypes}
import play.api.libs.json.{Format, JsNumber, JsObject, JsString, JsValue, Json}
import play.api.libs.ws.{DefaultWSCookie, StandaloneWSRequest}
import play.api.mvc.{AnyContent, Call, Cookie, Request, Session}
import play.api.test.FakeRequest
import uk.gov.hmrc.crypto.{ApplicationCrypto, PlainText}
import uk.gov.hmrc.uploaddocuments.journeys.FileUploadJourneyStateFormats
import uk.gov.hmrc.uploaddocuments.models._
import uk.gov.hmrc.uploaddocuments.repository.CacheRepository
import uk.gov.hmrc.uploaddocuments.services.{FileUploadJourneyService, MongoDBCachedJourneyService}
import uk.gov.hmrc.uploaddocuments.stubs.{ExternalApiStubs, UpscanInitiateStubs}
import uk.gov.hmrc.uploaddocuments.support.{SHA256, ServerISpec, StateMatchers, TestData, TestJourneyService}

import java.time.temporal.ChronoUnit
import java.time.{LocalDate, LocalDateTime, ZonedDateTime}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random
import uk.gov.hmrc.uploaddocuments.connectors.FileUploadResultPushConnector
import java.util.UUID
import uk.gov.hmrc.http.SessionKeys

class FileUploadJourneyISpec extends FileUploadJourneyISpecSetup with ExternalApiStubs with UpscanInitiateStubs {

  import journey.model.State._

  implicit val journeyId: JourneyId = JourneyId()

  val hostServiceId: String = UUID.randomUUID.toString

  val fileUploadSessionConfig =
    FileUploadSessionConfig(
      serviceId = hostServiceId,
      nonce = Nonce.random,
      continueUrl = s"$wireMockBaseUrlAsString/continue-url",
      backlinkUrl = s"$wireMockBaseUrlAsString/backlink-url",
      callbackUrl = s"$wireMockBaseUrlAsString/result-post-url"
    )

  val FILES_LIMIT = fileUploadSessionConfig.maximumNumberOfFiles

  "FileUploadJourneyController" when {

    "preferUploadMultipleFiles" should {
      "return false when jsenabled cookie NOT set" in {
        controller.preferUploadMultipleFiles(FakeRequest()) shouldBe false
      }

      "return true when jsenabled cookie set" in {
        controller.preferUploadMultipleFiles(
          FakeRequest().withCookies(Cookie(controller.COOKIE_JSENABLED, "true"))
        ) shouldBe true
      }
    }

    "successRedirect" should {
      "return /file-verification when jsenabled cookie NOT set" in {
        controller.successRedirect(FakeRequest()) should endWith(
          "/upload-documents/file-verification"
        )
      }

      "return /journey/:journeyId/file-verification when jsenabled cookie set" in {
        controller.successRedirect(
          fakeRequest(Cookie(controller.COOKIE_JSENABLED, "true"))
        ) should endWith(
          s"/upload-documents/journey/${SHA256.compute(journeyId.value)}/file-verification"
        )
      }
    }

    "errorRedirect" should {
      "return /file-rejected when jsenabled cookie NOT set" in {
        controller.errorRedirect(FakeRequest()) should endWith(
          "/upload-documents/file-rejected"
        )
      }

      "return /journey/:journeyId/file-rejected when jsenabled cookie set" in {
        controller.errorRedirect(
          fakeRequest(Cookie(controller.COOKIE_JSENABLED, "true"))
        ) should endWith(
          s"/upload-documents/journey/${SHA256.compute(journeyId.value)}/file-rejected"
        )
      }
    }

    "POST /initialize" should {
      "return 404 if wrong http method" in {
        journey.setState(Uninitialized)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        val result = await(request("/initialize").get())
        result.status shouldBe 404
        journey.getState shouldBe Uninitialized
      }

      "return 400 if malformed payload" in {
        journey.setState(Uninitialized)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        val result = await(request("/initialize").post(""))
        result.status shouldBe 400
        journey.getState shouldBe Uninitialized
      }

      "return 400 if cannot accept payload" in {
        journey.setState(Uninitialized)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        val result = await(
          request("/initialize")
            .post(
              Json.toJson(
                UploadedFile(
                  upscanReference = "jjSJKjksjJSJ",
                  downloadUrl = "https://aws.amzon.com/dummy.jpg",
                  uploadTimestamp = ZonedDateTime.parse("2007-12-03T10:15:30+01:00"),
                  checksum = "akskakslaklskalkskalksl",
                  fileName = "dummy.jpg",
                  fileMimeType = "image/jpg",
                  fileSize = 1024
                )
              )
            )
        )
        result.status shouldBe 400
        journey.getState shouldBe Uninitialized
      }

      "register config and empty file uploads" in {
        journey.setState(Uninitialized)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        val result = await(
          request("/initialize")
            .post(Json.toJson(FileUploadInitializationRequest(fileUploadSessionConfig, Seq.empty)))
        )
        result.status shouldBe 201
        journey.getState shouldBe Initialized(
          FileUploadContext(
            fileUploadSessionConfig,
            CallbackAuth.Any
          ),
          FileUploads()
        )
      }

      "register config and pre-existing file uploads" in {
        val preexistingUploads = Seq(
          UploadedFile(
            upscanReference = "jjSJKjksjJSJ",
            downloadUrl = "https://aws.amzon.com/dummy.jpg",
            uploadTimestamp = ZonedDateTime.parse("2007-12-03T10:15:30+01:00"),
            checksum = "akskakslaklskalkskalksl",
            fileName = "dummy.jpg",
            fileMimeType = "image/jpg",
            fileSize = 1024,
            cargo = Some(Json.obj("foo" -> JsString("bar")))
          )
        )
        journey.setState(Uninitialized)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        val result = await(
          request("/initialize")
            .post(
              Json.toJson(
                FileUploadInitializationRequest(
                  fileUploadSessionConfig,
                  preexistingUploads
                )
              )
            )
        )
        result.status shouldBe 201
        journey.getState shouldBe Initialized(
          FileUploadContext(
            fileUploadSessionConfig,
            CallbackAuth.Any
          ),
          FileUploads(preexistingUploads.map(_.toFileUpload))
        )
      }
    }

    "POST /wipe-out" should {
      "return 404 if wrong http method" in {
        val state = Initialized(
          FileUploadContext(
            fileUploadSessionConfig,
            CallbackAuth.Any
          ),
          FileUploads()
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        val result = await(request("/wipe-out").get())
        result.status shouldBe 404
        journey.getState shouldBe state
      }

      "return 204 and cleanup session" in {
        val state = Initialized(
          FileUploadContext(
            fileUploadSessionConfig,
            CallbackAuth.Any
          ),
          FileUploads()
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        val result = await(request("/wipe-out").post(""))
        result.status shouldBe 204
        journey.getState shouldBe Uninitialized
      }
    }

    "GET /" should {
      "show the start page when no cookie set" in {
        val state = Initialized(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads()
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val callbackUrl =
          appConfig.baseInternalCallbackUrl + s"/upload-documents/journey/${SHA256.compute(journeyId.value)}/callback-from-upscan"
        givenUpscanInitiateSucceeds(callbackUrl, hostServiceId)

        val result = await(request("/").get())

        result.status shouldBe 200
        result.body should include("url=/upload-documents/choose-files")

        journey.getState shouldBe state
      }

    }

    "GET /choose-files" should {
      "show the upload multiple files page when cookie set" in {
        val state = Initialized(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads()
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result = await(requestWithCookies("/choose-files", controller.COOKIE_JSENABLED -> "true").get())

        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("view.upload-multiple-files.title"))
        result.body should include(htmlEscapedMessage("view.upload-multiple-files.heading"))
        journey.getState shouldBe UploadMultipleFiles(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads()
        )
      }

      "show the upload single file per page when no cookie set" in {
        val state = Initialized(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads()
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val callbackUrl =
          appConfig.baseInternalCallbackUrl + s"/upload-documents/journey/${SHA256.compute(journeyId.value)}/callback-from-upscan"
        givenUpscanInitiateSucceeds(callbackUrl, hostServiceId)

        val result = await(request("/choose-files").get())

        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("view.upload-file.first.title"))
        result.body should include(htmlEscapedMessage("view.upload-file.first.heading"))

        journey.getState shouldBe UploadSingleFile(
          FileUploadContext(fileUploadSessionConfig),
          reference = "11370e18-6e24-453e-b45a-76d3e32ea33d",
          uploadRequest = UploadRequest(
            href = "https://bucketName.s3.eu-west-2.amazonaws.com",
            fields = Map(
              "Content-Type"            -> "application/xml",
              "acl"                     -> "private",
              "key"                     -> "xxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
              "policy"                  -> "xxxxxxxx==",
              "x-amz-algorithm"         -> "AWS4-HMAC-SHA256",
              "x-amz-credential"        -> "ASIAxxxxxxxxx/20180202/eu-west-2/s3/aws4_request",
              "x-amz-date"              -> "yyyyMMddThhmmssZ",
              "x-amz-meta-callback-url" -> callbackUrl,
              "x-amz-signature"         -> "xxxx",
              "success_action_redirect" -> "https://myservice.com/nextPage",
              "error_action_redirect"   -> "https://myservice.com/errorPage"
            )
          ),
          fileUploads = FileUploads(files =
            Seq(FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"))
          )
        )
      }

      "reload the upload multiple files page " in {
        val state = UploadMultipleFiles(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads()
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result = await(request("/choose-files").get())

        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("view.upload-multiple-files.title"))
        result.body should include(htmlEscapedMessage("view.upload-multiple-files.heading"))
        journey.getState shouldBe state
      }

      "retreat from finished to the upload multiple files page " in {
        val state = ContinueToHost(
          FileUploadContext(fileUploadSessionConfig),
          FileUploads()
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result = await(requestWithCookies("/choose-files", controller.COOKIE_JSENABLED -> "true").get())

        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("view.upload-multiple-files.title"))
        result.body should include(htmlEscapedMessage("view.upload-multiple-files.heading"))
        journey.getState shouldBe UploadMultipleFiles(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads()
        )
      }
    }

    "POST /initialize-upscan/:uploadId" should {
      "initialise first file upload" in {
        val state = UploadMultipleFiles(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads()
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        val callbackUrl =
          appConfig.baseInternalCallbackUrl + s"/upload-documents/journey/${SHA256.compute(journeyId.value)}/callback-from-upscan"
        givenUpscanInitiateSucceeds(callbackUrl, hostServiceId)

        val result = await(request("/initialize-upscan/001").post(""))

        result.status shouldBe 200
        val json = result.body[JsValue]
        (json \ "upscanReference").as[String] shouldBe "11370e18-6e24-453e-b45a-76d3e32ea33d"
        (json \ "uploadId").as[String] shouldBe "001"
        (json \ "uploadRequest").as[JsObject] shouldBe Json.obj(
          "href" -> "https://bucketName.s3.eu-west-2.amazonaws.com",
          "fields" -> Json.obj(
            "Content-Type"            -> "application/xml",
            "acl"                     -> "private",
            "key"                     -> "xxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
            "policy"                  -> "xxxxxxxx==",
            "x-amz-algorithm"         -> "AWS4-HMAC-SHA256",
            "x-amz-credential"        -> "ASIAxxxxxxxxx/20180202/eu-west-2/s3/aws4_request",
            "x-amz-date"              -> "yyyyMMddThhmmssZ",
            "x-amz-meta-callback-url" -> callbackUrl,
            "x-amz-signature"         -> "xxxx",
            "success_action_redirect" -> "https://myservice.com/nextPage",
            "error_action_redirect"   -> "https://myservice.com/errorPage"
          )
        )

        journey.getState shouldBe
          UploadMultipleFiles(
            FileUploadContext(fileUploadSessionConfig),
            fileUploads = FileUploads(files =
              Seq(
                FileUpload.Initiated(
                  Nonce.Any,
                  Timestamp.Any,
                  "11370e18-6e24-453e-b45a-76d3e32ea33d",
                  uploadId = Some("001"),
                  uploadRequest = Some(
                    UploadRequest(
                      href = "https://bucketName.s3.eu-west-2.amazonaws.com",
                      fields = Map(
                        "Content-Type"            -> "application/xml",
                        "acl"                     -> "private",
                        "key"                     -> "xxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
                        "policy"                  -> "xxxxxxxx==",
                        "x-amz-algorithm"         -> "AWS4-HMAC-SHA256",
                        "x-amz-credential"        -> "ASIAxxxxxxxxx/20180202/eu-west-2/s3/aws4_request",
                        "x-amz-date"              -> "yyyyMMddThhmmssZ",
                        "x-amz-meta-callback-url" -> callbackUrl,
                        "x-amz-signature"         -> "xxxx",
                        "success_action_redirect" -> "https://myservice.com/nextPage",
                        "error_action_redirect"   -> "https://myservice.com/errorPage"
                      )
                    )
                  )
                )
              )
            )
          )
      }

      "initialise next file upload" in {
        val state = UploadMultipleFiles(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads(
            Seq(FileUpload.Posted(Nonce.Any, Timestamp.Any, "23370e18-6e24-453e-b45a-76d3e32ea389"))
          )
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        val callbackUrl =
          appConfig.baseInternalCallbackUrl + s"/upload-documents/journey/${SHA256.compute(journeyId.value)}/callback-from-upscan"
        givenUpscanInitiateSucceeds(callbackUrl, hostServiceId)

        val result = await(request("/initialize-upscan/002").post(""))

        result.status shouldBe 200
        val json = result.body[JsValue]
        (json \ "upscanReference").as[String] shouldBe "11370e18-6e24-453e-b45a-76d3e32ea33d"
        (json \ "uploadId").as[String] shouldBe "002"
        (json \ "uploadRequest").as[JsObject] shouldBe Json.obj(
          "href" -> "https://bucketName.s3.eu-west-2.amazonaws.com",
          "fields" -> Json.obj(
            "Content-Type"            -> "application/xml",
            "acl"                     -> "private",
            "key"                     -> "xxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
            "policy"                  -> "xxxxxxxx==",
            "x-amz-algorithm"         -> "AWS4-HMAC-SHA256",
            "x-amz-credential"        -> "ASIAxxxxxxxxx/20180202/eu-west-2/s3/aws4_request",
            "x-amz-date"              -> "yyyyMMddThhmmssZ",
            "x-amz-meta-callback-url" -> callbackUrl,
            "x-amz-signature"         -> "xxxx",
            "success_action_redirect" -> "https://myservice.com/nextPage",
            "error_action_redirect"   -> "https://myservice.com/errorPage"
          )
        )

        journey.getState shouldBe
          UploadMultipleFiles(
            FileUploadContext(fileUploadSessionConfig),
            fileUploads = FileUploads(files =
              Seq(
                FileUpload.Posted(Nonce.Any, Timestamp.Any, "23370e18-6e24-453e-b45a-76d3e32ea389"),
                FileUpload.Initiated(
                  Nonce.Any,
                  Timestamp.Any,
                  "11370e18-6e24-453e-b45a-76d3e32ea33d",
                  uploadId = Some("002"),
                  uploadRequest = Some(
                    UploadRequest(
                      href = "https://bucketName.s3.eu-west-2.amazonaws.com",
                      fields = Map(
                        "Content-Type"            -> "application/xml",
                        "acl"                     -> "private",
                        "key"                     -> "xxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
                        "policy"                  -> "xxxxxxxx==",
                        "x-amz-algorithm"         -> "AWS4-HMAC-SHA256",
                        "x-amz-credential"        -> "ASIAxxxxxxxxx/20180202/eu-west-2/s3/aws4_request",
                        "x-amz-date"              -> "yyyyMMddThhmmssZ",
                        "x-amz-meta-callback-url" -> callbackUrl,
                        "x-amz-signature"         -> "xxxx",
                        "success_action_redirect" -> "https://myservice.com/nextPage",
                        "error_action_redirect"   -> "https://myservice.com/errorPage"
                      )
                    )
                  )
                )
              )
            )
          )
      }
    }

    "GET /choose-file" should {
      "show the upload page of first document" in {
        val state = Initialized(FileUploadContext(fileUploadSessionConfig), FileUploads())
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        val callbackUrl =
          appConfig.baseInternalCallbackUrl + s"/upload-documents/journey/${SHA256.compute(journeyId.value)}/callback-from-upscan"
        givenUpscanInitiateSucceeds(callbackUrl, hostServiceId)

        val result = await(request("/choose-file").get())

        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("view.upload-file.first.title"))
        result.body should include(htmlEscapedMessage("view.upload-file.first.heading"))

        journey.getState shouldBe UploadSingleFile(
          FileUploadContext(fileUploadSessionConfig),
          reference = "11370e18-6e24-453e-b45a-76d3e32ea33d",
          uploadRequest = UploadRequest(
            href = "https://bucketName.s3.eu-west-2.amazonaws.com",
            fields = Map(
              "Content-Type"            -> "application/xml",
              "acl"                     -> "private",
              "key"                     -> "xxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
              "policy"                  -> "xxxxxxxx==",
              "x-amz-algorithm"         -> "AWS4-HMAC-SHA256",
              "x-amz-credential"        -> "ASIAxxxxxxxxx/20180202/eu-west-2/s3/aws4_request",
              "x-amz-date"              -> "yyyyMMddThhmmssZ",
              "x-amz-meta-callback-url" -> callbackUrl,
              "x-amz-signature"         -> "xxxx",
              "success_action_redirect" -> "https://myservice.com/nextPage",
              "error_action_redirect"   -> "https://myservice.com/errorPage"
            )
          ),
          fileUploads = FileUploads(files =
            Seq(FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"))
          )
        )
      }

      "show the singular file uploaded page" in {
        val state = Initialized(
          FileUploadContext(fileUploadSessionConfig),
          FileUploads(
            Seq(
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "f029444f-415c-4dec-9cf2-36774ec63ab8",
                "https://bucketName.s3.eu-west-2.amazonaws.com?1235676",
                ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test.pdf",
                "application/pdf",
                4567890
              )
            )
          )
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        val callbackUrl =
          appConfig.baseInternalCallbackUrl + s"/upload-documents/journey/${SHA256.compute(journeyId.value)}/callback-from-upscan"
        givenUpscanInitiateSucceeds(callbackUrl, hostServiceId)

        val result = await(request("/choose-file").get())

        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("view.summary.singular.title", "1"))
        result.body should include(htmlEscapedMessage("view.summary.singular.heading", "1"))

        journey.getState shouldBe Summary(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads(files =
            Seq(
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "f029444f-415c-4dec-9cf2-36774ec63ab8",
                "https://bucketName.s3.eu-west-2.amazonaws.com?1235676",
                ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test.pdf",
                "application/pdf",
                4567890
              )
            )
          )
        )
      }

      "show the plural file uploaded page" in {
        val state = Initialized(
          FileUploadContext(fileUploadSessionConfig),
          FileUploads(
            Seq(
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "f029444f-415c-4dec-9cf2-36774ec63ab8",
                "https://bucketName.s3.eu-west-2.amazonaws.com?1235676",
                ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test.pdf",
                "application/pdf",
                4567890
              ),
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "f029444f-415c-4dec-9cf2-36774ec63fff",
                "https://bucketName.s3.eu-west-2.amazonaws.com?1235676",
                ZonedDateTime.parse("2019-06-11T19:17:21Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test2.jpeg",
                "image/jpeg",
                4567891
              )
            )
          )
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        val callbackUrl =
          appConfig.baseInternalCallbackUrl + s"/upload-documents/journey/${SHA256.compute(journeyId.value)}/callback-from-upscan"
        givenUpscanInitiateSucceeds(callbackUrl, hostServiceId)

        val result = await(request("/choose-file").get())

        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("view.summary.plural.title", "2"))
        result.body should include(htmlEscapedMessage("view.summary.plural.heading", "2"))

        journey.getState shouldBe Summary(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads(files =
            Seq(
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "f029444f-415c-4dec-9cf2-36774ec63ab8",
                "https://bucketName.s3.eu-west-2.amazonaws.com?1235676",
                ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test.pdf",
                "application/pdf",
                4567890
              ),
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "f029444f-415c-4dec-9cf2-36774ec63fff",
                "https://bucketName.s3.eu-west-2.amazonaws.com?1235676",
                ZonedDateTime.parse("2019-06-11T19:17:21Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test2.jpeg",
                "image/jpeg",
                4567891
              )
            )
          )
        )
      }
    }

    "GET /file-verification" should {
      "display waiting for file verification page" in {
        journey.setState(
          UploadSingleFile(
            FileUploadContext(fileUploadSessionConfig),
            "2b72fe99-8adf-4edb-865e-622ae710f77c",
            UploadRequest(href = "https://s3.bucket", fields = Map("callbackUrl" -> "https://foo.bar/callback")),
            FileUploads(files =
              Seq(
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Posted(Nonce.Any, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c")
              )
            )
          )
        )
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result = await(request("/file-verification").get())

        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("view.upload-file.waiting"))
        result.body should include(htmlEscapedMessage("view.upload-file.waiting"))

        journey.getState shouldBe (
          WaitingForFileVerification(
            FileUploadContext(fileUploadSessionConfig),
            "2b72fe99-8adf-4edb-865e-622ae710f77c",
            UploadRequest(href = "https://s3.bucket", fields = Map("callbackUrl" -> "https://foo.bar/callback")),
            FileUpload.Posted(Nonce.Any, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c"),
            FileUploads(files =
              Seq(
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Posted(Nonce.Any, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c")
              )
            )
          )
        )
      }
    }

    "GET /journey/:journeyId/file-rejected" should {
      "set current file upload status as rejected and return 204 NoContent" in {
        journey.setState(
          UploadSingleFile(
            FileUploadContext(fileUploadSessionConfig),
            "11370e18-6e24-453e-b45a-76d3e32ea33d",
            UploadRequest(href = "https://s3.bucket", fields = Map("callbackUrl" -> "https://foo.bar/callback")),
            FileUploads(files =
              Seq(
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Posted(Nonce.Any, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c")
              )
            )
          )
        )
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result1 =
          await(
            requestWithoutSessionId(
              s"/journey/${SHA256.compute(journeyId.value)}/file-rejected?key=11370e18-6e24-453e-b45a-76d3e32ea33d&errorCode=ABC123&errorMessage=ABC+123"
            ).get()
          )

        result1.status shouldBe 204
        result1.body.isEmpty shouldBe true
        journey.getState shouldBe (
          UploadSingleFile(
            FileUploadContext(fileUploadSessionConfig),
            "11370e18-6e24-453e-b45a-76d3e32ea33d",
            UploadRequest(href = "https://s3.bucket", fields = Map("callbackUrl" -> "https://foo.bar/callback")),
            FileUploads(files =
              Seq(
                FileUpload.Rejected(
                  Nonce.Any,
                  Timestamp.Any,
                  "11370e18-6e24-453e-b45a-76d3e32ea33d",
                  S3UploadError(
                    key = "11370e18-6e24-453e-b45a-76d3e32ea33d",
                    errorCode = "ABC123",
                    errorMessage = "ABC 123"
                  )
                ),
                FileUpload.Posted(Nonce.Any, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c")
              )
            ),
            Some(
              FileTransmissionFailed(
                S3UploadError("11370e18-6e24-453e-b45a-76d3e32ea33d", "ABC123", "ABC 123", None, None)
              )
            )
          )
        )
      }
    }

    "GET /journey/:journeyId/file-verification" should {
      "set current file upload status as posted and return 204 NoContent" in {
        journey.setState(
          UploadSingleFile(
            FileUploadContext(fileUploadSessionConfig),
            "11370e18-6e24-453e-b45a-76d3e32ea33d",
            UploadRequest(href = "https://s3.bucket", fields = Map("callbackUrl" -> "https://foo.bar/callback")),
            FileUploads(files =
              Seq(
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Posted(Nonce.Any, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c")
              )
            )
          )
        )
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result1 =
          await(requestWithoutSessionId(s"/journey/${SHA256.compute(journeyId.value)}/file-verification").get())

        result1.status shouldBe 202
        result1.body.isEmpty shouldBe true
        journey.getState shouldBe (
          WaitingForFileVerification(
            FileUploadContext(fileUploadSessionConfig),
            "11370e18-6e24-453e-b45a-76d3e32ea33d",
            UploadRequest(href = "https://s3.bucket", fields = Map("callbackUrl" -> "https://foo.bar/callback")),
            FileUpload.Posted(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
            FileUploads(files =
              Seq(
                FileUpload.Posted(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Posted(Nonce.Any, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c")
              )
            )
          )
        )
      }
    }

    "GET /file-verification/:reference/status" should {
      "return file verification status" in {

        val state = Summary(
          FileUploadContext(fileUploadSessionConfig),
          FileUploads(files =
            Seq(
              FileUpload.Initiated(
                Nonce.Any,
                Timestamp.Any,
                "11370e18-6e24-453e-b45a-76d3e32ea33d",
                uploadRequest =
                  Some(UploadRequest(href = "https://s3.amazonaws.com/bucket/123abc", fields = Map("foo1" -> "bar1")))
              ),
              FileUpload.Posted(Nonce.Any, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c"),
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "f029444f-415c-4dec-9cf2-36774ec63ab8",
                "https://bucketName.s3.eu-west-2.amazonaws.com?1235676",
                ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test.pdf",
                "application/pdf",
                4567890
              ),
              FileUpload.Failed(
                Nonce.Any,
                Timestamp.Any,
                "4b1e15a4-4152-4328-9448-4924d9aee6e2",
                UpscanNotification.FailureDetails(UpscanNotification.QUARANTINE, "some reason")
              ),
              FileUpload.Rejected(
                Nonce.Any,
                Timestamp.Any,
                "4b1e15a4-4152-4328-9448-4924d9aee6e3",
                details = S3UploadError("key", "errorCode", "Invalid file type.")
              ),
              FileUpload.Duplicate(
                Nonce.Any,
                Timestamp.Any,
                "4b1e15a4-4152-4328-9448-4924d9aee6e4",
                checksum = "0" * 64,
                existingFileName = "test.pdf",
                duplicateFileName = "test1.png"
              )
            )
          ),
          acknowledged = false
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result1 =
          await(
            request("/file-verification/11370e18-6e24-453e-b45a-76d3e32ea33d/status")
              .get()
          )
        result1.status shouldBe 200
        result1.body shouldBe """{"reference":"11370e18-6e24-453e-b45a-76d3e32ea33d","fileStatus":"NOT_UPLOADED","uploadRequest":{"href":"https://s3.amazonaws.com/bucket/123abc","fields":{"foo1":"bar1"}}}"""
        journey.getState shouldBe state

        val result2 =
          await(request("/file-verification/2b72fe99-8adf-4edb-865e-622ae710f77c/status").get())
        result2.status shouldBe 200
        result2.body shouldBe """{"reference":"2b72fe99-8adf-4edb-865e-622ae710f77c","fileStatus":"WAITING"}"""
        journey.getState shouldBe state

        val result3 =
          await(request("/file-verification/f029444f-415c-4dec-9cf2-36774ec63ab8/status").get())
        result3.status shouldBe 200
        result3.body shouldBe """{"reference":"f029444f-415c-4dec-9cf2-36774ec63ab8","fileStatus":"ACCEPTED","fileMimeType":"application/pdf","fileName":"test.pdf","fileSize":4567890,"previewUrl":"/upload-documents/preview/f029444f-415c-4dec-9cf2-36774ec63ab8/test.pdf"}"""
        journey.getState shouldBe state

        val result4 =
          await(request("/file-verification/4b1e15a4-4152-4328-9448-4924d9aee6e2/status").get())
        result4.status shouldBe 200
        result4.body shouldBe """{"reference":"4b1e15a4-4152-4328-9448-4924d9aee6e2","fileStatus":"FAILED","errorMessage":"The selected file contains a virus - upload a different one"}"""
        journey.getState shouldBe state

        val result5 =
          await(request("/file-verification/f0e317f5-d394-42cc-93f8-e89f4fc0114c/status").get())
        result5.status shouldBe 404
        journey.getState shouldBe state

        val result6 =
          await(request("/file-verification/4b1e15a4-4152-4328-9448-4924d9aee6e3/status").get())
        result6.status shouldBe 200
        result6.body shouldBe """{"reference":"4b1e15a4-4152-4328-9448-4924d9aee6e3","fileStatus":"REJECTED","errorMessage":"The selected file could not be uploaded"}"""
        journey.getState shouldBe state

        val result7 =
          await(request("/file-verification/4b1e15a4-4152-4328-9448-4924d9aee6e4/status").get())
        result7.status shouldBe 200
        result7.body shouldBe """{"reference":"4b1e15a4-4152-4328-9448-4924d9aee6e4","fileStatus":"DUPLICATE","errorMessage":"The selected file has already been uploaded"}"""
        journey.getState shouldBe state
      }
    }

    "GET /summary" should {
      "show uploaded singular file view" in {
        val state = Summary(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads(files = Seq(TestData.acceptedFileUpload))
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result = await(request("/summary").get())

        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("view.summary.singular.title", "1"))
        result.body should include(htmlEscapedMessage("view.summary.singular.heading", "1"))
        journey.getState shouldBe state
      }

      "show uploaded plural file view" in {
        val state = Summary(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads(files =
            Seq(
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "11370e18-6e24-453e-b45a-76d3e32ea33d",
                "https://s3.amazonaws.com/bucket/123",
                ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test2.pdf",
                "application/pdf",
                5234567
              ),
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "22370e18-6e24-453e-b45a-76d3e32ea33d",
                "https://s3.amazonaws.com/bucket/123",
                ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test1.png",
                "image/png",
                4567890
              )
            )
          )
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result = await(request("/summary").get())

        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("view.summary.plural.title", "2"))
        result.body should include(htmlEscapedMessage("view.summary.plural.heading", "2"))
        journey.getState shouldBe state
      }

      "show file upload summary view" in {
        val state = Summary(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads(files = for (i <- 1 to FILES_LIMIT) yield TestData.acceptedFileUpload)
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result = await(request("/summary").get())

        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("view.summary.plural.title", FILES_LIMIT.toString))
        result.body should include(htmlEscapedMessage("view.summary.plural.heading", FILES_LIMIT.toString))
        journey.getState shouldBe state
      }
    }

    "POST /summary" should {

      "show upload a file view for export when yes and number of files below the limit" in {

        val fileUploads = FileUploads(files = for (i <- 1 until FILES_LIMIT) yield TestData.acceptedFileUpload)
        val state = Summary(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        val callbackUrl =
          appConfig.baseInternalCallbackUrl + s"/upload-documents/journey/${SHA256.compute(journeyId.value)}/callback-from-upscan"
        givenUpscanInitiateSucceeds(callbackUrl, hostServiceId)

        val result = await(
          request("/summary")
            .post(Map("uploadAnotherFile" -> "yes"))
        )

        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("view.upload-file.next.title"))
        result.body should include(htmlEscapedMessage("view.upload-file.next.heading"))
        journey.getState shouldBe UploadSingleFile(
          FileUploadContext(fileUploadSessionConfig),
          reference = "11370e18-6e24-453e-b45a-76d3e32ea33d",
          uploadRequest = UploadRequest(
            href = "https://bucketName.s3.eu-west-2.amazonaws.com",
            fields = Map(
              "Content-Type"            -> "application/xml",
              "acl"                     -> "private",
              "key"                     -> "xxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
              "policy"                  -> "xxxxxxxx==",
              "x-amz-algorithm"         -> "AWS4-HMAC-SHA256",
              "x-amz-credential"        -> "ASIAxxxxxxxxx/20180202/eu-west-2/s3/aws4_request",
              "x-amz-date"              -> "yyyyMMddThhmmssZ",
              "x-amz-meta-callback-url" -> callbackUrl,
              "x-amz-signature"         -> "xxxx",
              "success_action_redirect" -> "https://myservice.com/nextPage",
              "error_action_redirect"   -> "https://myservice.com/errorPage"
            )
          ),
          fileUploads = FileUploads(files =
            fileUploads.files ++
              Seq(FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"))
          )
        )
      }

      "show upload a file view when yes and number of files below the limit" in {

        val fileUploads = FileUploads(files = for (i <- 1 until FILES_LIMIT) yield TestData.acceptedFileUpload)
        val state = Summary(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        val callbackUrl =
          appConfig.baseInternalCallbackUrl + s"/upload-documents/journey/${SHA256.compute(journeyId.value)}/callback-from-upscan"
        givenUpscanInitiateSucceeds(callbackUrl, hostServiceId)

        val result = await(
          request("/summary")
            .post(Map("uploadAnotherFile" -> "yes"))
        )

        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("view.upload-file.next.title"))
        result.body should include(htmlEscapedMessage("view.upload-file.next.heading"))
        journey.getState shouldBe UploadSingleFile(
          FileUploadContext(fileUploadSessionConfig),
          reference = "11370e18-6e24-453e-b45a-76d3e32ea33d",
          uploadRequest = UploadRequest(
            href = "https://bucketName.s3.eu-west-2.amazonaws.com",
            fields = Map(
              "Content-Type"            -> "application/xml",
              "acl"                     -> "private",
              "key"                     -> "xxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
              "policy"                  -> "xxxxxxxx==",
              "x-amz-algorithm"         -> "AWS4-HMAC-SHA256",
              "x-amz-credential"        -> "ASIAxxxxxxxxx/20180202/eu-west-2/s3/aws4_request",
              "x-amz-date"              -> "yyyyMMddThhmmssZ",
              "x-amz-meta-callback-url" -> callbackUrl,
              "x-amz-signature"         -> "xxxx",
              "success_action_redirect" -> "https://myservice.com/nextPage",
              "error_action_redirect"   -> "https://myservice.com/errorPage"
            )
          ),
          fileUploads = FileUploads(files =
            fileUploads.files ++
              Seq(FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"))
          )
        )
      }

      "redirect to the continue_url when yes and files number limit has been reached" in {

        val fileUploads = FileUploads(files = for (i <- 1 to FILES_LIMIT) yield TestData.acceptedFileUpload)
        val state = Summary(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        givenSomePage(200, "/continue-url", "Welcome back at host 1!")

        val result = await(
          request("/summary")
            .post(Map("uploadAnotherFile" -> "yes"))
        )

        journey.getState shouldBe ContinueToHost(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads
        )

        result.status shouldBe 200
        result.body shouldBe "Welcome back at host 1!"
      }

      "redirect to the continue_url when no and files number below the limit" in {

        val fileUploads = FileUploads(files = for (i <- 1 until FILES_LIMIT) yield TestData.acceptedFileUpload)
        val state = Summary(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        givenSomePage(200, "/continue-url", "Welcome back at host 2!")

        val result = await(
          request("/summary")
            .post(Map("uploadAnotherFile" -> "no"))
        )

        journey.getState shouldBe ContinueToHost(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads
        )

        result.status shouldBe 200
        result.body shouldBe "Welcome back at host 2!"
      }

      "redirect to the continue_url when no and files number above the limit" in {

        val fileUploads = FileUploads(files = for (i <- 1 to FILES_LIMIT) yield TestData.acceptedFileUpload)
        val state = Summary(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))
        givenSomePage(200, "/continue-url", "Welcome back at host 3!")

        val result = await(
          request("/summary")
            .post(Map("uploadAnotherFile" -> "no"))
        )

        journey.getState shouldBe ContinueToHost(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads
        )

        result.status shouldBe 200
        result.body shouldBe "Welcome back at host 3!"
      }
    }

    "GET /file-rejected" should {
      "show upload document again" in {
        journey.setState(
          UploadSingleFile(
            FileUploadContext(fileUploadSessionConfig),
            "2b72fe99-8adf-4edb-865e-622ae710f77c",
            UploadRequest(href = "https://s3.bucket", fields = Map("callbackUrl" -> "https://foo.bar/callback")),
            FileUploads(files =
              Seq(
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c")
              )
            )
          )
        )
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result = await(
          request(
            "/file-rejected?key=2b72fe99-8adf-4edb-865e-622ae710f77c&errorCode=EntityTooLarge&errorMessage=Entity+Too+Large"
          ).get()
        )

        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("view.upload-file.first.title"))
        result.body should include(htmlEscapedMessage("view.upload-file.first.heading"))
        journey.getState shouldBe UploadSingleFile(
          FileUploadContext(fileUploadSessionConfig),
          "2b72fe99-8adf-4edb-865e-622ae710f77c",
          UploadRequest(href = "https://s3.bucket", fields = Map("callbackUrl" -> "https://foo.bar/callback")),
          FileUploads(files =
            Seq(
              FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
              FileUpload.Initiated(Nonce.Any, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c")
            )
          ),
          Some(
            FileTransmissionFailed(
              S3UploadError("2b72fe99-8adf-4edb-865e-622ae710f77c", "EntityTooLarge", "Entity Too Large")
            )
          )
        )
      }
    }

    "POST /file-rejected" should {
      "mark file upload as rejected" in {
        journey.setState(
          UploadMultipleFiles(
            FileUploadContext(fileUploadSessionConfig),
            FileUploads(files =
              Seq(
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c")
              )
            )
          )
        )
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result = await(
          request("/file-rejected").post(
            Json.obj(
              "key"          -> "2b72fe99-8adf-4edb-865e-622ae710f77c",
              "errorCode"    -> "EntityTooLarge",
              "errorMessage" -> "Entity Too Large"
            )
          )
        )

        result.status shouldBe 201

        journey.getState shouldBe UploadMultipleFiles(
          FileUploadContext(fileUploadSessionConfig),
          FileUploads(files =
            Seq(
              FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
              FileUpload.Rejected(
                Nonce.Any,
                Timestamp.Any,
                "2b72fe99-8adf-4edb-865e-622ae710f77c",
                S3UploadError("2b72fe99-8adf-4edb-865e-622ae710f77c", "EntityTooLarge", "Entity Too Large")
              )
            )
          )
        )
      }
    }

    "GET /uploaded/:reference/remove" should {
      "remove file from upload list by reference" in {
        givenResultPushEndpoint(
          "/result-post-url",
          FileUploadResultPushConnector.Payload.from(
            FileUploadContext(fileUploadSessionConfig),
            FileUploads(files =
              Seq(
                FileUpload.Accepted(
                  Nonce.Any,
                  Timestamp.Any,
                  "22370e18-6e24-453e-b45a-76d3e32ea33d",
                  "https://s3.amazonaws.com/bucket/123",
                  ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                  "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                  "test1.png",
                  "image/png",
                  4567890
                )
              )
            ),
            "http://base.external.callback"
          ),
          204
        )
        val state = Summary(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads(files =
            Seq(
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "11370e18-6e24-453e-b45a-76d3e32ea33d",
                "https://s3.amazonaws.com/bucket/123",
                ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test2.pdf",
                "application/pdf",
                5234567
              ),
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "22370e18-6e24-453e-b45a-76d3e32ea33d",
                "https://s3.amazonaws.com/bucket/123",
                ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test1.png",
                "image/png",
                4567890
              )
            )
          )
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result = await(request("/uploaded/11370e18-6e24-453e-b45a-76d3e32ea33d/remove").get())

        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("view.summary.singular.title", "1"))
        result.body should include(htmlEscapedMessage("view.summary.singular.heading", "1"))
        journey.getState shouldBe Summary(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads(files =
            Seq(
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "22370e18-6e24-453e-b45a-76d3e32ea33d",
                "https://s3.amazonaws.com/bucket/123",
                ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test1.png",
                "image/png",
                4567890
              )
            )
          )
        )
        eventually(
          verifyResultPushHasHappened("/result-post-url", 1)
        )
      }
    }

    "POST /uploaded/:reference/remove" should {
      "remove file from upload list by reference" in {
        givenResultPushEndpoint(
          "/result-post-url",
          FileUploadResultPushConnector.Payload.from(
            FileUploadContext(fileUploadSessionConfig),
            FileUploads(files =
              Seq(
                FileUpload.Accepted(
                  Nonce.Any,
                  Timestamp.Any,
                  "22370e18-6e24-453e-b45a-76d3e32ea33d",
                  "https://s3.amazonaws.com/bucket/123",
                  ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                  "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                  "test1.png",
                  "image/png",
                  4567890
                )
              )
            ),
            "http://base.external.callback"
          ),
          204
        )
        val state = UploadMultipleFiles(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads(files =
            Seq(
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "11370e18-6e24-453e-b45a-76d3e32ea33d",
                "https://s3.amazonaws.com/bucket/123",
                ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test2.pdf",
                "application/pdf",
                5234567
              ),
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "22370e18-6e24-453e-b45a-76d3e32ea33d",
                "https://s3.amazonaws.com/bucket/123",
                ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test1.png",
                "image/png",
                4567890
              )
            )
          )
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result = await(request("/uploaded/11370e18-6e24-453e-b45a-76d3e32ea33d/remove").post(""))

        result.status shouldBe 204

        journey.getState shouldBe UploadMultipleFiles(
          FileUploadContext(fileUploadSessionConfig),
          fileUploads = FileUploads(files =
            Seq(
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "22370e18-6e24-453e-b45a-76d3e32ea33d",
                "https://s3.amazonaws.com/bucket/123",
                ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test1.png",
                "image/png",
                4567890
              )
            )
          )
        )
        eventually(
          verifyResultPushHasHappened("/result-post-url", 1)
        )
      }
    }

    "GET /preview/:reference/:fileName" should {
      "stream the uploaded file content back if it exists" in {
        val bytes = Array.ofDim[Byte](1024 * 1024)
        Random.nextBytes(bytes)
        val upscanUrl = stubForFileDownload(200, bytes, "test.pdf")

        val state = Summary(
          FileUploadContext(fileUploadSessionConfig),
          FileUploads(files =
            Seq(
              FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
              FileUpload.Posted(Nonce.Any, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c"),
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "f029444f-415c-4dec-9cf2-36774ec63ab8",
                upscanUrl,
                ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test.pdf",
                "application/pdf",
                4567890
              ),
              FileUpload.Failed(
                Nonce.Any,
                Timestamp.Any,
                "4b1e15a4-4152-4328-9448-4924d9aee6e2",
                UpscanNotification.FailureDetails(UpscanNotification.QUARANTINE, "some reason")
              )
            )
          ),
          acknowledged = false
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result =
          await(
            request("/preview/f029444f-415c-4dec-9cf2-36774ec63ab8/test.pdf")
              .get()
          )
        result.status shouldBe 200
        result.header("Content-Type") shouldBe Some("application/pdf")
        result.header("Content-Length") shouldBe Some(s"${bytes.length}")
        result.header("Content-Disposition") shouldBe Some("""inline; filename="test.pdf"; filename*=utf-8''test.pdf""")
        result.bodyAsBytes.toArray[Byte] shouldBe bytes
        journey.getState shouldBe state
      }

      "return error page if file does not exist" in {
        val upscanUrl = stubForFileDownloadFailure(404, "test.pdf")

        val state = Summary(
          FileUploadContext(fileUploadSessionConfig),
          FileUploads(files =
            Seq(
              FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
              FileUpload.Posted(Nonce.Any, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c"),
              FileUpload.Accepted(
                Nonce.Any,
                Timestamp.Any,
                "f029444f-415c-4dec-9cf2-36774ec63ab8",
                upscanUrl,
                ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                "test.pdf",
                "application/pdf",
                4567890
              ),
              FileUpload.Failed(
                Nonce.Any,
                Timestamp.Any,
                "4b1e15a4-4152-4328-9448-4924d9aee6e2",
                UpscanNotification.FailureDetails(UpscanNotification.QUARANTINE, "some reason")
              )
            )
          ),
          acknowledged = false
        )
        journey.setState(state)
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result =
          await(
            request("/preview/f029444f-415c-4dec-9cf2-36774ec63ab8/test.pdf")
              .get()
          )
        result.status shouldBe 200
        result.body should include(htmlEscapedPageTitle("global.error.500.title"))
        result.body should include(htmlEscapedMessage("global.error.500.heading"))
        journey.getState shouldBe state
      }
    }

    "GET /journey/:journeyId/file-posted" should {
      "set current file upload status as posted and return 201 Created" in {
        journey.setState(
          UploadMultipleFiles(
            FileUploadContext(fileUploadSessionConfig),
            FileUploads(files =
              Seq(
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Posted(Nonce.Any, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c")
              )
            )
          )
        )
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result =
          await(
            requestWithoutSessionId(
              s"/journey/${SHA256.compute(journeyId.value)}/file-posted?key=11370e18-6e24-453e-b45a-76d3e32ea33d&bucket=foo"
            ).get()
          )

        result.status shouldBe 201
        result.body.isEmpty shouldBe true
        result.headerValues(HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN) shouldBe Seq("*")
        journey.getState should beState(
          UploadMultipleFiles(
            FileUploadContext(fileUploadSessionConfig),
            FileUploads(files =
              Seq(
                FileUpload.Posted(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Posted(Nonce.Any, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c")
              )
            )
          )
        )
      }
    }

    "POST /journey/:journeyId/callback-from-upscan" should {
      "return 400 if callback body invalid" in {
        val nonce = Nonce.random
        journey.setState(
          UploadMultipleFiles(
            FileUploadContext(fileUploadSessionConfig),
            FileUploads(files =
              Seq(
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Posted(nonce, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c")
              )
            )
          )
        )
        val result =
          await(
            requestWithoutSessionId(s"/journey/${SHA256.compute(journeyId.value)}/callback-from-upscan/$nonce")
              .withHttpHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON)
              .post(
                Json.obj(
                  "reference" -> JsString("2b72fe99-8adf-4edb-865e-622ae710f77c")
                )
              )
          )

        result.status shouldBe 400
        journey.getState should beState(
          UploadMultipleFiles(
            FileUploadContext(fileUploadSessionConfig),
            FileUploads(files =
              Seq(
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Posted(nonce, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c")
              )
            )
          )
        )
        eventually {
          verifyResultPushHasNotHappened("/continue")
        }
      }

      "modify file status to Accepted and return 204" in {
        val nonce = Nonce.random
        givenResultPushEndpoint(
          "/result-post-url",
          FileUploadResultPushConnector.Payload.from(
            FileUploadContext(fileUploadSessionConfig),
            FileUploads(files =
              Seq(
                FileUpload.Accepted(
                  nonce,
                  Timestamp.Any,
                  "2b72fe99-8adf-4edb-865e-622ae710f77c",
                  "https://foo.bar/XYZ123/foo.pdf",
                  ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                  "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                  "foo.pdf",
                  "application/pdf",
                  1
                ),
                FileUpload.Accepted(
                  Nonce.Any,
                  Timestamp.Any,
                  "1c72fe99-8adf-4edb-865e-622ae710f88b",
                  "https://foo.bar/XYZ123/bar.pdf",
                  ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                  "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775101",
                  "bar.pdf",
                  "application/pdf",
                  1,
                  Some(Json.obj("bar" -> 1))
                )
              )
            ),
            "http://base.external.callback"
          ),
          204
        )
        journey.setState(
          UploadMultipleFiles(
            FileUploadContext(fileUploadSessionConfig),
            FileUploads(files =
              Seq(
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Posted(nonce, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c"),
                FileUpload.Accepted(
                  Nonce.Any,
                  Timestamp.Any,
                  "1c72fe99-8adf-4edb-865e-622ae710f88b",
                  "https://foo.bar/XYZ123/bar.pdf",
                  ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                  "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775101",
                  "bar.pdf",
                  "application/pdf",
                  1,
                  Some(Json.obj("bar" -> 1))
                )
              )
            )
          )
        )
        val result =
          await(
            requestWithoutSessionId(s"/journey/${SHA256.compute(journeyId.value)}/callback-from-upscan/$nonce")
              .withHttpHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON)
              .post(
                Json.obj(
                  "reference"   -> JsString("2b72fe99-8adf-4edb-865e-622ae710f77c"),
                  "fileStatus"  -> JsString("READY"),
                  "downloadUrl" -> JsString("https://foo.bar/XYZ123/foo.pdf"),
                  "uploadDetails" -> Json.obj(
                    "uploadTimestamp" -> JsString("2018-04-24T09:30:00Z"),
                    "checksum"        -> JsString("396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100"),
                    "fileName"        -> JsString("foo.pdf"),
                    "fileMimeType"    -> JsString("application/pdf"),
                    "size"            -> JsNumber(1)
                  )
                )
              )
          )

        result.status shouldBe 204
        journey.getState should beState(
          UploadMultipleFiles(
            FileUploadContext(fileUploadSessionConfig),
            FileUploads(files =
              Seq(
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Accepted(
                  nonce,
                  Timestamp.Any,
                  "2b72fe99-8adf-4edb-865e-622ae710f77c",
                  "https://foo.bar/XYZ123/foo.pdf",
                  ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                  "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                  "foo.pdf",
                  "application/pdf",
                  1
                ),
                FileUpload.Accepted(
                  Nonce.Any,
                  Timestamp.Any,
                  "1c72fe99-8adf-4edb-865e-622ae710f88b",
                  "https://foo.bar/XYZ123/bar.pdf",
                  ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                  "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775101",
                  "bar.pdf",
                  "application/pdf",
                  1,
                  Some(Json.obj("bar" -> 1))
                )
              )
            )
          )
        )
        eventually {
          verifyResultPushHasHappened("/result-post-url", 1)
        }
      }

      "keep file status Accepted and return 204" in {
        val nonce = Nonce.random
        journey.setState(
          UploadMultipleFiles(
            FileUploadContext(fileUploadSessionConfig),
            FileUploads(files =
              Seq(
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Accepted(
                  nonce,
                  Timestamp.Any,
                  "2b72fe99-8adf-4edb-865e-622ae710f77c",
                  "https://foo.bar/XYZ123/foo.pdf",
                  ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                  "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                  "foo.pdf",
                  "application/pdf",
                  1
                )
              )
            )
          )
        )
        val result =
          await(
            requestWithoutSessionId(s"/journey/${SHA256.compute(journeyId.value)}/callback-from-upscan/$nonce")
              .withHttpHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON)
              .post(
                Json.obj(
                  "reference"   -> JsString("2b72fe99-8adf-4edb-865e-622ae710f77c"),
                  "fileStatus"  -> JsString("READY"),
                  "downloadUrl" -> JsString("https://foo.bar/XYZ123/foo.pdf"),
                  "uploadDetails" -> Json.obj(
                    "uploadTimestamp" -> JsString("2018-04-24T09:30:00Z"),
                    "checksum"        -> JsString("396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100"),
                    "fileName"        -> JsString("foo.pdf"),
                    "fileMimeType"    -> JsString("application/pdf"),
                    "size"            -> JsNumber(1)
                  )
                )
              )
          )

        result.status shouldBe 204
        journey.getState should beState(
          UploadMultipleFiles(
            FileUploadContext(fileUploadSessionConfig),
            FileUploads(files =
              Seq(
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Accepted(
                  nonce,
                  Timestamp.Any,
                  "2b72fe99-8adf-4edb-865e-622ae710f77c",
                  "https://foo.bar/XYZ123/foo.pdf",
                  ZonedDateTime.parse("2018-04-24T09:30:00Z"),
                  "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
                  "foo.pdf",
                  "application/pdf",
                  1
                )
              )
            )
          )
        )
        eventually {
          verifyResultPushHasNotHappened("/continue")
        }
      }

      "change nothing if nonce not matching" in {
        val nonce = Nonce.random
        journey.setState(
          UploadMultipleFiles(
            FileUploadContext(fileUploadSessionConfig),
            FileUploads(files =
              Seq(
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Posted(nonce, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c")
              )
            )
          )
        )
        val result =
          await(
            requestWithoutSessionId(s"/journey/${SHA256.compute(journeyId.value)}/callback-from-upscan/${Nonce.random}")
              .withHttpHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON)
              .post(
                Json.obj(
                  "reference"   -> JsString("2b72fe99-8adf-4edb-865e-622ae710f77c"),
                  "fileStatus"  -> JsString("READY"),
                  "downloadUrl" -> JsString("https://foo.bar/XYZ123/foo.pdf"),
                  "uploadDetails" -> Json.obj(
                    "uploadTimestamp" -> JsString("2018-04-24T09:30:00Z"),
                    "checksum"        -> JsString("396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100"),
                    "fileName"        -> JsString("foo.pdf"),
                    "fileMimeType"    -> JsString("application/pdf"),
                    "size"            -> JsNumber(1)
                  )
                )
              )
          )

        result.status shouldBe 204
        journey.getState should beState(
          UploadMultipleFiles(
            FileUploadContext(fileUploadSessionConfig),
            FileUploads(files =
              Seq(
                FileUpload.Initiated(Nonce.Any, Timestamp.Any, "11370e18-6e24-453e-b45a-76d3e32ea33d"),
                FileUpload.Posted(nonce, Timestamp.Any, "2b72fe99-8adf-4edb-865e-622ae710f77c")
              )
            )
          )
        )
        eventually {
          verifyResultPushHasNotHappened("/continue")
        }
      }
    }

    "OPTIONS /journey/:journeyId/file-rejected" should {
      "return 201 with access control header" in {
        val result =
          await(
            requestWithoutSessionId(s"/journey/${SHA256.compute(journeyId.value)}/file-rejected")
              .options()
          )
        result.status shouldBe 201
        result.body.isEmpty shouldBe true
        result.headerValues(HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN) shouldBe Seq("*")
      }
    }

    "OPTIONS /journey/:journeyId/file-posted" should {
      "return 201 with access control header" in {
        val result =
          await(
            requestWithoutSessionId(s"/journey/${SHA256.compute(journeyId.value)}/file-posted")
              .options()
          )
        result.status shouldBe 201
        result.body.isEmpty shouldBe true
        result.headerValues(HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN) shouldBe Seq("*")
      }
    }

    "GET /foo" should {
      "return an error page not found" in {
        val state = journey.getState
        givenAuthorisedForEnrolment(Enrolment("HMRC-XYZ", "EORINumber", "foo"))

        val result = await(request("/foo").get())

        result.status shouldBe 404
        result.body should include("Page not found")
        journey.getState shouldBe state
      }
    }
  }
}

trait FileUploadJourneyISpecSetup extends ServerISpec with StateMatchers {

  val dateTime = LocalDateTime.now()
  val dateTimeOfArrival = dateTime.plusDays(1).truncatedTo(ChronoUnit.MINUTES)

  import play.api.i18n._
  implicit val messages: Messages = MessagesImpl(Lang("en"), app.injector.instanceOf[MessagesApi])

  val today = LocalDate.now
  val (y, m, d) = (today.getYear(), today.getMonthValue(), today.getDayOfMonth())

  lazy val controller = app.injector.instanceOf[FileUploadJourneyController]

  lazy val journey = new TestJourneyService[JourneyId]
    with FileUploadJourneyService[JourneyId] with MongoDBCachedJourneyService[JourneyId] {

    override lazy val actorSystem: ActorSystem = app.injector.instanceOf[ActorSystem]
    override lazy val cacheRepository = app.injector.instanceOf[CacheRepository]
    override lazy val applicationCrypto = app.injector.instanceOf[ApplicationCrypto]

    override val stateFormats: Format[model.State] =
      FileUploadJourneyStateFormats.formats

    override def getJourneyId(journeyId: JourneyId): Option[String] =
      Some(SHA256.compute(journeyId.value))
  }

  final def fakeRequest(cookies: Cookie*)(implicit
    journeyId: JourneyId
  ): Request[AnyContent] =
    fakeRequest("GET", "/", cookies: _*)

  final def fakeRequest(method: String, path: String, cookies: Cookie*)(implicit
    journeyId: JourneyId
  ): Request[AnyContent] =
    FakeRequest(Call(method, path))
      .withCookies(cookies: _*)
      .withSession(SessionKeys.sessionId -> journeyId.value)

  final def request(path: String)(implicit journeyId: JourneyId): StandaloneWSRequest = {
    val sessionCookie =
      sessionCookieBaker
        .encodeAsCookie(Session(Map(SessionKeys.sessionId -> journeyId.value)))
    wsClient
      .url(s"$baseUrl$path")
      .withCookies(
        DefaultWSCookie(
          sessionCookie.name,
          sessionCookieCrypto.crypto.encrypt(PlainText(sessionCookie.value)).value
        )
      )
  }

  final def requestWithCookies(path: String, cookies: (String, String)*)(implicit
    journeyId: JourneyId
  ): StandaloneWSRequest = {
    val sessionCookie =
      sessionCookieBaker
        .encodeAsCookie(Session(Map(SessionKeys.sessionId -> journeyId.value)))

    wsClient
      .url(s"$baseUrl$path")
      .withCookies(
        (cookies.map(c => DefaultWSCookie(c._1, c._2)) :+ DefaultWSCookie(
          sessionCookie.name,
          sessionCookieCrypto.crypto.encrypt(PlainText(sessionCookie.value)).value
        )): _*
      )
  }

}
