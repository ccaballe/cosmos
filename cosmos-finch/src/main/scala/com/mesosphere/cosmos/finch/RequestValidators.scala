package com.mesosphere.cosmos.finch

import com.mesosphere.cosmos.finch.FinchExtensions._
import com.mesosphere.cosmos.http.Authorization
import com.mesosphere.cosmos.http.CompoundMediaType
import com.mesosphere.cosmos.http.MediaType
import com.mesosphere.cosmos.http.RequestSession
import com.twitter.finagle.http.Fields
import io.finch._
import shapeless.::
import shapeless.HNil

object RequestValidators {

  def noBody[Res](implicit
    produces: DispatchingMediaTypedEncoder[Res]
  ): Endpoint[EndpointContext[Unit, Res]] = {
    baseValidator(produces).map { case authorization :: responseEncoder :: HNil =>
      EndpointContext((), RequestSession(authorization, contentType = None), responseEncoder)
    }
  }

  def standard[Req, Res](implicit
    accepts: MediaTypedRequestDecoder[Req],
    produces: DispatchingMediaTypedEncoder[Res]
  ): Endpoint[EndpointContext[Req, Res]] = {
    val contentTypeRule = beTheExpectedType(accepts.mediaTypedDecoder.mediaType)
    val contentTypeValidator = header(Fields.ContentType).as[MediaType].should(contentTypeRule)

    val bodyValidator = body[Req, Application.Json](accepts.decoder, accepts.classTag)

    val allValidators = baseValidator(produces) :: contentTypeValidator :: bodyValidator
    allValidators.map {
      case authorization :: responseEncoder :: contentType :: requestBody :: HNil =>
        val session = RequestSession(authorization, Some(contentType))
        EndpointContext(requestBody, session, responseEncoder)
    }
  }

  // TODO package-add: Unit tests in RequestValidatorsSpec
  def selectedBody[Req, Res](implicit
    accepts: DispatchingMediaTypedBodyParser[Req],
    produces: DispatchingMediaTypedEncoder[Res]
  ): Endpoint[EndpointContext[Req, Res]] = {
    val contentTypeValidator = header(Fields.ContentType)
      .as[MediaType]
      .map { contentType =>
        accepts(contentType) match {
          case Some(bodyParser) => contentType :: bodyParser :: HNil
          case _ =>
            throw incompatibleContentTypeHeader(accepts.mediaTypes, contentType)
        }
      }

    val allValidators = baseValidator(produces) :: contentTypeValidator :: binaryBody
    allValidators.map {
      case authorization :: responseEncoder :: contentType :: bodyParser :: bodyBytes :: HNil =>
        val requestBody = bodyParser(bodyBytes).get  // Exceptions will be caught by Endpoint.map()
        val session = RequestSession(authorization, Some(contentType))
        EndpointContext(requestBody, session, responseEncoder)
    }
  }

  private[this] def baseValidator[Res](
    produces: DispatchingMediaTypedEncoder[Res]
  ): Endpoint[Option[Authorization] :: MediaTypedEncoder[Res] :: HNil] = {
    val accept = header(Fields.Accept)
      .as[CompoundMediaType]
      .map { accept =>
        produces(accept) match {
          case Some(x) => x
          case None =>
            throw incompatibleAcceptHeader(produces.mediaTypes, accept.mediaTypes)
        }
      }
    val auth = headerOption(Fields.Authorization).map(_.map(Authorization))
    auth :: accept
  }
}
