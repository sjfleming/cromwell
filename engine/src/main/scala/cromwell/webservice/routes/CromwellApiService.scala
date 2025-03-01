package cromwell.webservice.routes

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.pattern.{AskTimeoutException, ask}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import common.exception.AggregatedMessageException
import common.util.VersionUtil
import cromwell.core.abort._
import cromwell.core.{path => _, _}
import cromwell.engine.backend.BackendConfiguration
import cromwell.engine.instrumentation.HttpInstrumentation
import cromwell.engine.workflow.WorkflowManagerActor.WorkflowNotFoundException
import cromwell.engine.workflow.lifecycle.execution.callcaching.CallCacheDiffActor.{CachedCallNotFoundException, CallCacheDiffActorResponse, FailedCallCacheDiffResponse, SuccessfulCallCacheDiffResponse}
import cromwell.engine.workflow.lifecycle.execution.callcaching.CallCacheDiffActorJsonFormatting.successfulResponseJsonFormatter
import cromwell.engine.workflow.lifecycle.execution.callcaching.{CallCacheDiffActor, CallCacheDiffQueryParameter}
import cromwell.engine.workflow.workflowstore.SqlWorkflowStore.NotInOnHoldStateException
import cromwell.engine.workflow.workflowstore.{WorkflowStoreActor, WorkflowStoreEngineActor}
import cromwell.server.CromwellShutdown
import cromwell.services._
import cromwell.services.healthmonitor.ProtoHealthMonitorServiceActor.{GetCurrentStatus, StatusCheckResponse}
import cromwell.services.metadata.MetadataService._
import cromwell.webservice.WebServiceUtils.EnhancedThrowable
import cromwell.webservice.WorkflowJsonSupport._
import cromwell.webservice._

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future, TimeoutException}
import scala.io.Source
import scala.util.{Failure, Success, Try}

trait CromwellApiService extends HttpInstrumentation with MetadataRouteSupport with WomtoolRouteSupport with WebServiceUtils with WesCromwellRouteSupport {

  import CromwellApiService._

  implicit def actorRefFactory: ActorRefFactory

  implicit val materializer: ActorMaterializer
  implicit val ec: ExecutionContext

  val workflowStoreActor: ActorRef
  val workflowManagerActor: ActorRef
  val serviceRegistryActor: ActorRef

  val engineRoutes = concat(
    path("engine" / Segment / "stats") { _ =>
      get {
        completeResponse(StatusCodes.Forbidden, APIResponse.fail(new RuntimeException("The /stats endpoint is currently disabled.")), warnings = Seq.empty)
      }
    },
    path("engine" / Segment / "version") { _ =>
      get {
        complete(versionResponse)
      }
    },
    path("engine" / Segment / "status") { _ =>
      onComplete(serviceRegistryActor.ask(GetCurrentStatus).mapTo[StatusCheckResponse]) {
        case Success(status) =>
          val httpCode = if (status.ok) StatusCodes.OK else StatusCodes.InternalServerError
          complete(ToResponseMarshallable((httpCode, status.systems)))
        case Failure(e: TimeoutException) => e.failRequest(StatusCodes.ServiceUnavailable)
        case Failure(_) => new RuntimeException("Unable to gather engine status").failRequest(StatusCodes.InternalServerError)
      }
    }
  )

  val workflowRoutes =
    path("workflows" / Segment / "backends") { _ =>
      get {
        instrumentRequest {
          complete(ToResponseMarshallable(backendResponse))
        }
      }
    } ~
      path("workflows" / Segment / "callcaching" / "diff") { _ =>
        parameterSeq { parameters =>
          get {
            instrumentRequest {
              CallCacheDiffQueryParameter.fromParameters(parameters) match {
                case Valid(queryParameter) =>
                  val diffActor = actorRefFactory.actorOf(CallCacheDiffActor.props(serviceRegistryActor), "CallCacheDiffActor-" + UUID.randomUUID())
                  onComplete(diffActor.ask(queryParameter).mapTo[CallCacheDiffActorResponse]) {
                    case Success(r: SuccessfulCallCacheDiffResponse) => complete(r)
                    case Success(r: FailedCallCacheDiffResponse) => r.reason.errorRequest(StatusCodes.InternalServerError)
                    case Failure(_: AskTimeoutException) if CromwellShutdown.shutdownInProgress() => serviceShuttingDownResponse
                    case Failure(e: CachedCallNotFoundException) => e.errorRequest(StatusCodes.NotFound)
                    case Failure(e: TimeoutException) => e.failRequest(StatusCodes.ServiceUnavailable)
                    case Failure(e) => e.errorRequest(StatusCodes.InternalServerError)
                  }
                case Invalid(errors) =>
                  val e = AggregatedMessageException("Wrong parameters for call cache diff query", errors.toList)
                  e.errorRequest(StatusCodes.BadRequest)
              }
            }
          }
        }
      } ~
      path("workflows" / Segment / Segment / "timing") { (_, possibleWorkflowId) =>
        instrumentRequest {
          onComplete(validateWorkflowIdInMetadata(possibleWorkflowId, serviceRegistryActor)) {
            case Success(workflowId) => completeTimingRouteResponse(metadataLookupForTimingRoute(workflowId))
            case Failure(e: UnrecognizedWorkflowException) => e.failRequest(StatusCodes.NotFound)
            case Failure(e: InvalidWorkflowException) => e.failRequest(StatusCodes.BadRequest)
            case Failure(e) => e.failRequest(StatusCodes.InternalServerError)
          }
        }
      } ~
      path("workflows" / Segment / Segment / "abort") { (_, possibleWorkflowId) =>
        post {
          instrumentRequest {
            abortWorkflow(possibleWorkflowId, workflowStoreActor, workflowManagerActor)
          }
        }
      } ~
      path("workflows" / Segment) { _ =>
        post {
          instrumentRequest {
            entity(as[Multipart.FormData]) { formData =>
              submitRequest(formData, isSingleSubmission = true)
            }
          }
        }
      } ~
      path("workflows" / Segment / "batch") { _ =>
        post {
          instrumentRequest {
            entity(as[Multipart.FormData]) { formData =>
              submitRequest(formData, isSingleSubmission = false)
            }
          }
        }
      } ~
      path("workflows" / Segment / Segment / "releaseHold") { (_, possibleWorkflowId) =>
        post {
          instrumentRequest {
            val response = validateWorkflowIdInMetadata(possibleWorkflowId, serviceRegistryActor) flatMap { workflowId =>
              workflowStoreActor.ask(WorkflowStoreActor.WorkflowOnHoldToSubmittedCommand(workflowId)).mapTo[WorkflowStoreEngineActor.WorkflowOnHoldToSubmittedResponse]
            }
            onComplete(response) {
              case Success(WorkflowStoreEngineActor.WorkflowOnHoldToSubmittedFailure(_, e: NotInOnHoldStateException)) => e.errorRequest(StatusCodes.Forbidden)
              case Success(WorkflowStoreEngineActor.WorkflowOnHoldToSubmittedFailure(_, e)) => e.errorRequest(StatusCodes.InternalServerError)
              case Success(r: WorkflowStoreEngineActor.WorkflowOnHoldToSubmittedSuccess) => completeResponse(StatusCodes.OK, toResponse(r.workflowId, WorkflowSubmitted), Seq.empty)
              case Failure(e: UnrecognizedWorkflowException) => e.failRequest(StatusCodes.NotFound)
              case Failure(e: InvalidWorkflowException) => e.failRequest(StatusCodes.BadRequest)
              case Failure(e) => e.errorRequest(StatusCodes.InternalServerError)
            }
          }
        }
      } ~ metadataRoutes


  private def metadataLookupForTimingRoute(workflowId: WorkflowId): Future[MetadataJsonResponse] = {
    val includeKeys = NonEmptyList.of("start", "end", "executionStatus", "executionEvents", "subWorkflowMetadata")
    val readMetadataRequest = (w: WorkflowId) => GetSingleWorkflowMetadataAction(w, Option(includeKeys), None, expandSubWorkflows = true)

    serviceRegistryActor.ask(readMetadataRequest(workflowId)).mapTo[MetadataJsonResponse]
  }

  private def completeTimingRouteResponse(metadataResponse: Future[MetadataJsonResponse]) = {
    onComplete(metadataResponse) {
      case Success(r: SuccessfulMetadataJsonResponse) =>

        Try(Source.fromResource("workflowTimings/workflowTimings.html").mkString) match {
          case Success(wfTimingsContent) =>
            val response = HttpResponse(entity = wfTimingsContent.replace("\"{{REPLACE_THIS_WITH_METADATA}}\"", r.responseJson.toString))
            complete(response.withEntity(response.entity.withContentType(`text/html(UTF-8)`)))
          case Failure(e) => completeResponse(StatusCodes.InternalServerError, APIResponse.fail(new RuntimeException("Error while loading workflowTimings.html", e)), Seq.empty)
        }
      case Success(r: FailedMetadataJsonResponse) => r.reason.errorRequest(StatusCodes.InternalServerError)
      case Failure(_: AskTimeoutException) if CromwellShutdown.shutdownInProgress() => serviceShuttingDownResponse
      case Failure(e: TimeoutException) => e.failRequest(StatusCodes.ServiceUnavailable)
      case Failure(e) => e.failRequest(StatusCodes.InternalServerError)
    }
  }
}

  object CromwellApiService {

    import spray.json._

    /**
      * Sends a request to abort the workflow. Provides configurable success & error handlers to allow
      * for different API endpoints to provide different effects in the appropriate situations, e.g. HTTP codes
      * and error messages
      */
    def abortWorkflow(possibleWorkflowId: String,
                      workflowStoreActor: ActorRef,
                      workflowManagerActor: ActorRef,
                      successHandler: PartialFunction[SuccessfulAbortResponse, Route] = standardAbortSuccessHandler,
                      errorHandler: PartialFunction[Throwable, Route] = standardAbortErrorHandler)
                     (implicit timeout: Timeout): Route = {
      handleExceptions(ExceptionHandler(errorHandler)) {
        Try(WorkflowId.fromString(possibleWorkflowId)) match {
          case Success(workflowId) =>
            val response = workflowStoreActor.ask(WorkflowStoreActor.AbortWorkflowCommand(workflowId)).mapTo[AbortResponse]
            onComplete(response) {
              case Success(x: SuccessfulAbortResponse) => successHandler(x)
              case Success(x: WorkflowAbortFailureResponse) => throw x.failure
              case Failure(e) => throw e
            }
          case Failure(_) => throw InvalidWorkflowException(possibleWorkflowId)
        }
      }
    }

    /**
      * The abort success handler for typical cases, i.e. cromwell's API.
      */
    private def standardAbortSuccessHandler: PartialFunction[SuccessfulAbortResponse, Route] = {
      case WorkflowAbortedResponse(id) => complete(ToResponseMarshallable(WorkflowAbortResponse(id.toString, WorkflowAborted.toString)))
      case WorkflowAbortRequestedResponse(id) => complete(ToResponseMarshallable(WorkflowAbortResponse(id.toString, WorkflowAborting.toString)))
    }

    /**
      * The abort error handler for typical cases, i.e. cromwell's API
      */
    private def standardAbortErrorHandler: PartialFunction[Throwable, Route] = {
      case e: InvalidWorkflowException => e.failRequest(StatusCodes.BadRequest)
      case e: WorkflowNotFoundException => e.errorRequest(StatusCodes.NotFound)
      case _: AskTimeoutException if CromwellShutdown.shutdownInProgress() => serviceShuttingDownResponse
      case e: TimeoutException => e.failRequest(StatusCodes.ServiceUnavailable)
      case e: Exception => e.errorRequest(StatusCodes.InternalServerError)
    }

    def validateWorkflowIdInMetadata(possibleWorkflowId: String,
                                     serviceRegistryActor: ActorRef)
                                    (implicit timeout: Timeout, executor: ExecutionContext): Future[WorkflowId] = {
      Try(WorkflowId.fromString(possibleWorkflowId)) match {
        case Success(w) =>
          serviceRegistryActor.ask(ValidateWorkflowIdInMetadata(w)).mapTo[WorkflowValidationResponse] flatMap {
            case RecognizedWorkflowId => Future.successful(w)
            case UnrecognizedWorkflowId => validateWorkflowIdInMetadataSummaries(possibleWorkflowId, serviceRegistryActor)
            case FailedToCheckWorkflowId(t) => Future.failed(t)
          }
        case Failure(_) => Future.failed(InvalidWorkflowException(possibleWorkflowId))
      }
    }

    def validateWorkflowIdInMetadataSummaries(possibleWorkflowId: String,
                                              serviceRegistryActor: ActorRef)
                                             (implicit timeout: Timeout, executor: ExecutionContext): Future[WorkflowId] = {
      Try(WorkflowId.fromString(possibleWorkflowId)) match {
        case Success(w) =>
          serviceRegistryActor.ask(ValidateWorkflowIdInMetadataSummaries(w)).mapTo[WorkflowValidationResponse] map {
            case RecognizedWorkflowId => w
            case UnrecognizedWorkflowId => throw UnrecognizedWorkflowException(w)
            case FailedToCheckWorkflowId(t) => throw t
          }
        case Failure(_) => Future.failed(InvalidWorkflowException(possibleWorkflowId))
      }
    }

    final case class BackendResponse(supportedBackends: List[String], defaultBackend: String)

    final case class UnrecognizedWorkflowException(id: WorkflowId) extends Exception(s"Unrecognized workflow ID: $id")

    final case class InvalidWorkflowException(possibleWorkflowId: String) extends Exception(s"Invalid workflow ID: '$possibleWorkflowId'.")

    val cromwellVersion = VersionUtil.getVersion("cromwell-engine")
    val swaggerUiVersion = VersionUtil.getVersion("swagger-ui", VersionUtil.sbtDependencyVersion("swaggerUi"))
    val backendResponse = BackendResponse(BackendConfiguration.AllBackendEntries.map(_.name).sorted, BackendConfiguration.DefaultBackendEntry.name)
    val versionResponse = JsObject(Map("cromwell" -> cromwellVersion.toJson))
    val serviceShuttingDownResponse = new Exception("Cromwell service is shutting down.").failRequest(StatusCodes.ServiceUnavailable)
  }
