package fusion.core.http.headers

import java.time.Instant

import akka.http.scaladsl.model.headers.ModeledCustomHeader
import akka.http.scaladsl.model.headers.ModeledCustomHeaderCompanion
import fusion.common.constant.FusionConstants

import scala.util.Try

final class `X-Service`(override val value: String) extends ModeledCustomHeader[`X-Service`] {
  override def companion: ModeledCustomHeaderCompanion[`X-Service`] = `X-Service`
  override def renderInRequests(): Boolean                          = true
  override def renderInResponses(): Boolean                         = true
}

object `X-Service` extends ModeledCustomHeaderCompanion[`X-Service`] {
  override def name: String                           = FusionConstants.X_SERVER
  override def parse(value: String): Try[`X-Service`] = Try(new `X-Service`(value))
}

final class `X-Trace-Id`(override val value: String) extends ModeledCustomHeader[`X-Trace-Id`] {
  override def companion: ModeledCustomHeaderCompanion[`X-Trace-Id`] = `X-Trace-Id`
  override def renderInRequests(): Boolean                           = true
  override def renderInResponses(): Boolean                          = true
}

object `X-Trace-Id` extends ModeledCustomHeaderCompanion[`X-Trace-Id`] {
  override def name: String                            = FusionConstants.X_TRACE_NAME
  override def parse(value: String): Try[`X-Trace-Id`] = Try(new `X-Trace-Id`(value))
}

final class `X-Request-Time`(override val value: String) extends ModeledCustomHeader[`X-Request-Time`] {
  override def companion: ModeledCustomHeaderCompanion[`X-Request-Time`] = `X-Request-Time`
  override def renderInRequests(): Boolean                               = true
  override def renderInResponses(): Boolean                              = true
}

object `X-Request-Time` extends ModeledCustomHeaderCompanion[`X-Request-Time`] {
  override def name: String                                = FusionConstants.X_REQUEST_TIME
  override def parse(value: String): Try[`X-Request-Time`] = Try(new `X-Request-Time`(value))
  def fromInstantNow()                                     = `X-Request-Time`(Instant.now().toString)
}

final class `X-Span-Time`(override val value: String) extends ModeledCustomHeader[`X-Span-Time`] {
  override def companion: ModeledCustomHeaderCompanion[`X-Span-Time`] = `X-Span-Time`
  override def renderInRequests(): Boolean                            = true
  override def renderInResponses(): Boolean                           = true
}

object `X-Span-Time` extends ModeledCustomHeaderCompanion[`X-Span-Time`] {
  override def name: String                             = FusionConstants.X_SPAN_TIME
  override def parse(value: String): Try[`X-Span-Time`] = Try(new `X-Span-Time`(value))

  def fromXRequestTime(h: `X-Request-Time`) =
    `X-Span-Time`(java.time.Duration.between(Instant.parse(h.value), Instant.now()).toString)
}
