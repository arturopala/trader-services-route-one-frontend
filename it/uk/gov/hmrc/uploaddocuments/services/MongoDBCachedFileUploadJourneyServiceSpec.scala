package uk.gov.hmrc.uploaddocuments.services

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.uploaddocuments.models.{FileUploadContext, FileUploadInitializationRequest, FileUploadSessionConfig, FileUploads, Nonce}
import uk.gov.hmrc.uploaddocuments.support.{AppISpec, SHA256}

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

class MongoDBCachedFileUploadJourneyServiceSpec extends AppISpec {

  lazy val service: MongoDBCachedFileUploadJourneyService =
    app.injector.instanceOf[MongoDBCachedFileUploadJourneyService]

  import service.model.{State, Transitions}

  implicit val hc: HeaderCarrier =
    HeaderCarrier()
      .withExtraHeaders("FileUploadJourney" -> SHA256.compute(UUID.randomUUID.toString))

  val fileUploadContext =
    FileUploadContext(config = FileUploadSessionConfig("dummy-id", Nonce.random, "/foo", "/bar", "/zoo"))
  val request =
    FileUploadInitializationRequest(config = fileUploadContext.config, existingFiles = Seq.empty)

  "MongoDBCachedFileUploadJourneyService" should {
    "apply initialize transition" in {
      await(service.apply(Transitions.initialize(None)(request))) shouldBe (
        (
          State.Initialized(fileUploadContext, FileUploads()),
          List(State.Uninitialized)
        )
      )
    }

    "keep breadcrumbs when no change in state" in {
      service.updateBreadcrumbs(
        State.Initialized(fileUploadContext, FileUploads()),
        State.Initialized(fileUploadContext, FileUploads()),
        Nil
      ) shouldBe Nil
    }

    "update breadcrumbs when new state" in {
      service.updateBreadcrumbs(
        State.Initialized(fileUploadContext, FileUploads()),
        State.ContinueToHost(fileUploadContext, FileUploads()),
        Nil
      ) shouldBe List(
        State.ContinueToHost(fileUploadContext, FileUploads(List()))
      )
    }

    "trim breadcrumbs when returning back to the previous state" in {
      service.updateBreadcrumbs(
        State.Initialized(fileUploadContext, FileUploads()),
        State.ContinueToHost(fileUploadContext, FileUploads()),
        List(State.Initialized(fileUploadContext, FileUploads()))
      ) shouldBe Nil
    }
  }

}
