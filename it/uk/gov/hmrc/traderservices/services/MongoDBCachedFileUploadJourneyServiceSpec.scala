package uk.gov.hmrc.traderservices.services

import uk.gov.hmrc.traderservices.support.AppISpec
import uk.gov.hmrc.http.HeaderCarrier
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.UUID
import uk.gov.hmrc.traderservices.models.FileUploadInitializationRequest
import uk.gov.hmrc.traderservices.models.FileUploadSessionConfig
import uk.gov.hmrc.traderservices.models.FileUploads

class MongoDBCachedFileUploadJourneyServiceSpec extends AppISpec {

  lazy val service: MongoDBCachedFileUploadJourneyService =
    app.injector.instanceOf[MongoDBCachedFileUploadJourneyService]

  import service.model.{State, Transitions}

  implicit val hc: HeaderCarrier =
    HeaderCarrier()
      .withExtraHeaders("FileUploadJourney" -> UUID.randomUUID.toString)

  val fileUploadConfig = FileUploadSessionConfig("/foo", "/bar")
  val request =
    FileUploadInitializationRequest(config = fileUploadConfig, existingFiles = Seq.empty)

  "MongoDBCachedFileUploadJourneyService" should {
    "apply initialize transition" in {
      await(service.apply(Transitions.initialize(request))) shouldBe (
        (
          State.Initialized(FileUploadSessionConfig("/foo", "/bar"), FileUploads()),
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
        State.ContinueToHost(FileUploadSessionConfig("/foo", "/bar"), FileUploads(List()))
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
