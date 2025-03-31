package com.klubraum.stripe

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{
  ContentType,
  ContentTypes,
  HttpEntity,
  HttpMethods as AkkaHttpMethods,
  HttpRequest as AkkaHttpRequest,
  HttpResponse as AkkaHttpResponse,
  Uri
}
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.scaladsl.StreamConverters
import akka.util.ByteString
import com.stripe.exception.ApiConnectionException
import com.stripe.net.ApiResource.RequestMethod
import com.stripe.net.{
  ApiResource,
  HttpClient,
  HttpHeaders as StripeHeaders,
  StripeRequest,
  StripeResponse,
  StripeResponseStream
}
import com.stripe.util.StreamUtils

import java.io.IOException
import java.net.{ ConnectException, SocketTimeoutException }
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.jdk.CollectionConverters.*
import scala.util.{ Failure, Success, Try }

/** A Stripe HttpClient implementation using Akka HTTP.
  *
  * @param connectTimeout
  *   Connect timeout for Akka HTTP requests.
  * @param readTimeout
  *   Total request timeout (approximates read timeout).
  * @param system
  *   Implicit Akka ActorSystem (Typed).
  */
class AkkaHttpClient(
    connectTimeout: FiniteDuration = 10.seconds, // Default connect timeout
    readTimeout: FiniteDuration = 30.seconds // Default read/total timeout
)(implicit system: ActorSystem[_])
    extends HttpClient {

  implicit val ec: ExecutionContext = system.executionContext
  private val http = Http(system.classicSystem)

  // Note: The Stripe HttpClient interface is blocking, so we must block here.
  // The base HttpClient handles retries; we just perform one request attempt.

  @throws[ApiConnectionException]
  override def request(request: StripeRequest): StripeResponse = {
    // Use the unstream implementation and buffer the result
    val responseStream = requestStreamInternal(request)
    try {
      // Block until the body is fully read and buffered
      val bodyString: String = StreamUtils.readToEnd(responseStream.body, ApiResource.CHARSET)
      responseStream.body.close()
      new StripeResponse(responseStream.code, responseStream.headers, bodyString)
    } catch {
      case e: IOException =>
        throw mapException(e, request.options.getConnectTimeout, request.options.getReadTimeout, request.url.toString)
    } finally {
      // Ensure resources are cleaned up even if unstream fails partially
      // (though unstream should handle internal cleanup on success/failure)
      try responseStream.body.close()
      catch { case _: IOException => /* Ignore close exception */ }
    }
  }

  @throws[ApiConnectionException]
  override def requestStream(request: StripeRequest): StripeResponseStream = {
    requestStreamInternal(request)
  }

  // Internal method to avoid code duplication between request and requestStream
  private def requestStreamInternal(request: StripeRequest): StripeResponseStream = {
    val akkaRequest = buildAkkaRequest(request)

    // Combine connect and read timeouts for the Await duration.
    // Akka HTTP handles connect timeout internally via settings,
    // but Await needs a total duration. Use Stripe's read timeout
    // plus a buffer, or our default.
    val reqConnectTimeout = Duration(request.options.getConnectTimeout.toLong, MILLISECONDS)
    val reqReadTimeout = Duration(request.options.getReadTimeout.toLong, MILLISECONDS)
    val awaitDuration = Seq(reqConnectTimeout, reqReadTimeout, connectTimeout, readTimeout).max // Be generous

    val futureResponse: Future[AkkaHttpResponse] =
      http.singleRequest(akkaRequest)

    Try(Await.result(futureResponse, awaitDuration)) match {
      case Success(akkaResponse) =>
        // Convert Akka Response Headers to Stripe Headers
        val stripeHeaders = buildStripeHeaders(akkaResponse)

        // Create an InputStream from the Akka Stream entity
        // Note: Reading this InputStream blocks.
        val inputStream = akkaResponse.entity.dataBytes
          .runWith(StreamConverters.asInputStream(readTimeout)) // Timeout for stream reading

        new StripeResponseStream(akkaResponse.status.intValue(), stripeHeaders, inputStream)

      case Failure(e) =>
        // Map Akka HTTP/Timeout exceptions to Stripe's ApiConnectionException
        throw mapException(e, reqConnectTimeout.toMillis.toInt, reqReadTimeout.toMillis.toInt, request.url.toString)
    }
  }

  private def buildAkkaRequest(request: StripeRequest): AkkaHttpRequest = {
    val akkaMethod = request.method match {
      case RequestMethod.GET    => AkkaHttpMethods.GET
      case RequestMethod.POST   => AkkaHttpMethods.POST
      case RequestMethod.DELETE => AkkaHttpMethods.DELETE
    }

    val uri = Uri(request.url.toString) // Use the full URL provided by Stripe

    // Combine Stripe headers with User-Agent headers
    val stripeHeadersMap =
      request.headers().map().asScala.toMap.view.mapValues(_.asScala.toSeq) // Convert Java Map/List to Scala Map/Seq
    val userAgentHeaders = Map(
      "User-Agent" -> Seq(HttpClient.buildUserAgentString(request)),
      "X-Stripe-Client-User-Agent" -> Seq(HttpClient.buildXStripeClientUserAgentString())
    )
    val allHeaders = (stripeHeadersMap ++ userAgentHeaders).flatMap { case (name, values) =>
      values.map(value => RawHeader(name, value))
    }.toList

    val entity = request.content match {
      case null => HttpEntity.Empty
      case content =>
        HttpEntity(
          ContentType.parse(content.contentType) match {
            case Right(contentType) => contentType
            case Left(errors) =>
              system.log.warn(
                s"Could not parse content type '${content.contentType}', defaulting to application/octet-stream. Errors: ${errors
                    .mkString(", ")}"
              )
              ContentTypes.`application/octet-stream` // Fallback
          },
          ByteString(content.byteArrayContent)
        )
    }

    AkkaHttpRequest(method = akkaMethod, uri = uri, headers = allHeaders, entity = entity)
  }

  private def buildStripeHeaders(response: AkkaHttpResponse): StripeHeaders = {
    val headersMap = response.headers
      .groupBy(_.name())
      .view
      .mapValues(_.map(_.value()).toList.asJava) // Group by name, convert values to Java List
      .toMap
      .asJava // Convert final map to Java Map
    StripeHeaders.of(headersMap)
  }

  private def mapException(e: Throwable, connTimeout: Int, rdTimeout: Int, url: String): ApiConnectionException = {
    val message = s"Error connecting to Stripe ($url): ${e.getMessage}"
    val cause = e match {
      // Specific Akka HTTP / Java network exceptions
      case ce: ConnectException        => ce
      case ste: SocketTimeoutException => ste // Often wrapped by Akka/Future TimeoutException
      case _: TimeoutException =>
        new SocketTimeoutException(
          s"Request timed out after $rdTimeout (or connection after $connTimeout)"
        ) // Map Future timeout
      // Add more specific Akka HTTP exceptions if needed (e.g., Pool-related)
      case ioe: IOException => ioe // Generic IO
      case other            => other // Keep original cause otherwise
    }
    new ApiConnectionException(message, cause)
  }
}
