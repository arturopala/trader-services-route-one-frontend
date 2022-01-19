package uk.gov.hmrc.uploaddocuments.services

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.uploaddocuments.models.{FileUploadInitializationRequest, FileUploadSessionConfig, FileUploads}
import uk.gov.hmrc.uploaddocuments.support.AppISpec

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.uploaddocuments.models.Nonce

class MongoDBCachedFileUploadJourneyServiceSpec extends AppISpec {

  lazy val service: MongoDBCachedFileUploadJourneyService =
    app.injector.instanceOf[MongoDBCachedFileUploadJourneyService]

  import service.model.{State, Transitions}

  implicit val hc: HeaderCarrier =
    HeaderCarrier()
      .withExtraHeaders("FileUploadJourney" -> UUID.randomUUID.toString)

  val fileUploadConfig = FileUploadSessionConfig("dummy-id", Nonce.random, "/foo", "/bar", "/zoo")
  val request =
    FileUploadInitializationRequest(config = fileUploadConfig, existingFiles = Seq.empty)

  "MongoDBCachedFileUploadJourneyService" should {
    "apply initialize transition" in {
      await(service.apply(Transitions.initialize(request))) shouldBe (
        (
          State.Initialized(fileUploadConfig, FileUploads()),
          List(State.Uninitialized)
        )
      )
    }

    "keep breadcrumbs when no change in state" in {
      service.updateBreadcrumbs(
        State.Initialized(fileUploadConfig, FileUploads()),
        State.Initialized(fileUploadConfig, FileUploads()),
        Nil
      ) shouldBe Nil
    }

    "update breadcrumbs when new state" in {
      service.updateBreadcrumbs(
        State.Initialized(fileUploadConfig, FileUploads()),
        State.ContinueToHost(fileUploadConfig, FileUploads()),
        Nil
      ) shouldBe List(
        State.ContinueToHost(fileUploadConfig, FileUploads(List()))
      )
    }

    "trim breadcrumbs when returning back to the previous state" in {
      service.updateBreadcrumbs(
        State.Initialized(fileUploadConfig, FileUploads()),
        State.ContinueToHost(fileUploadConfig, FileUploads()),
        List(State.Initialized(fileUploadConfig, FileUploads()))
      ) shouldBe Nil
    }
  }

}
