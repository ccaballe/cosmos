package com.mesosphere.cosmos

import com.mesosphere.cosmos.circe.Encoders._
import com.mesosphere.cosmos.handler._
import com.mesosphere.cosmos.http.MediaTypes
import com.mesosphere.cosmos.repository.PackageSourcesStorage
import com.mesosphere.cosmos.repository.UniverseClient
import com.mesosphere.cosmos.repository.ZooKeeperStorage
import com.mesosphere.cosmos.rpc.v1.circe.MediaTypedDecoders._
import com.mesosphere.cosmos.rpc.v1.circe.MediaTypedEncoders._
import com.mesosphere.cosmos.rpc.v2.circe.MediaTypedEncoders._
import com.netaporter.uri.Uri
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.util.Try
import io.circe.Json
import io.finch._
import io.finch.circe._
import io.finch.circe.dropNullKeys._
import io.github.benwhitehead.finch.FinchServer
import shapeless.HNil

private[cosmos] final class Cosmos(
  uninstallHandler: EndpointHandler[rpc.v1.model.UninstallRequest, rpc.v1.model.UninstallResponse, MediaTypes.UninstallResponseType],
  packageInstallHandler: EndpointHandler[rpc.v1.model.InstallRequest, rpc.v2.model.InstallResponse,MediaTypes.V2InstallResponseType],
  packageRenderHandler: EndpointHandler[rpc.v1.model.RenderRequest, rpc.v1.model.RenderResponse, MediaTypes.RenderResponseType],
  packageSearchHandler: EndpointHandler[rpc.v1.model.SearchRequest, rpc.v1.model.SearchResponse, MediaTypes.SearchResponseType],
  packageDescribeHandler: EndpointHandler[rpc.v1.model.DescribeRequest, internal.model.PackageDefinition, MediaTypes.DescribeRequestType],
  packageListVersionsHandler: EndpointHandler[rpc.v1.model.ListVersionsRequest, rpc.v1.model.ListVersionsResponse, MediaTypes.ListVersionsResponseType],
  listHandler: EndpointHandler[rpc.v1.model.ListRequest, rpc.v1.model.ListResponse,MediaTypes.V1ListResponseType],
  listRepositoryHandler: EndpointHandler[rpc.v1.model.PackageRepositoryListRequest, rpc.v1.model.PackageRepositoryListResponse,MediaTypes.PackageRepositoryListResponseType],
  addRepositoryHandler: EndpointHandler[rpc.v1.model.PackageRepositoryAddRequest, rpc.v1.model.PackageRepositoryAddResponse,MediaTypes.PackageRepositoryAddResponseType],
  deleteRepositoryHandler: EndpointHandler[rpc.v1.model.PackageRepositoryDeleteRequest, rpc.v1.model.PackageRepositoryDeleteResponse,MediaTypes.PackageRepositoryDeleteResponseType],
  capabilitiesHandler: CapabilitiesHandler
)(implicit statsReceiver: StatsReceiver = NullStatsReceiver) {

  import Cosmos._

  lazy val logger = org.slf4j.LoggerFactory.getLogger(classOf[Cosmos])

  val packageInstall: Endpoint[Response] = {
    route(post("package" / "install"), packageInstallHandler)(RequestReaders.standard)
  }

  val packageUninstall: Endpoint[Response] = {
    route(post("package" / "uninstall"), uninstallHandler)(RequestReaders.standard)
  }

  val packageDescribe: Endpoint[Response] = {
    route(post("package" / "describe"), packageDescribeHandler)(RequestReaders.standard)
  }

  val packageRender: Endpoint[Response] = {
    route(post("package" / "render"), packageRenderHandler)(RequestReaders.standard)
  }

  val packageListVersions: Endpoint[Response] = {
    route(post("package" / "list-versions"), packageListVersionsHandler)(RequestReaders.standard)
  }

  val packageSearch: Endpoint[Response] = {
    route(post("package" / "search"), packageSearchHandler)(RequestReaders.standard)
  }

  val packageList: Endpoint[Response] = {
    route(post("package" / "list"), listHandler)(RequestReaders.standard)
  }

  val capabilities: Endpoint[Response] = {
    route(get("capabilities"), capabilitiesHandler)(RequestReaders.noBody)
  }

  val packageListSources: Endpoint[Response] = {
    route(post("package" / "repository" / "list"), listRepositoryHandler)(RequestReaders.standard)
  }

  val packageAddSource: Endpoint[Response] = {
    route(post("package" / "repository" / "add"), addRepositoryHandler)(RequestReaders.standard)
  }

  val packageDeleteSource: Endpoint[Response] = {
    route(post("package" / "repository" / "delete"), deleteRepositoryHandler)(RequestReaders.standard)
  }

  val service: Service[Request, Response] = {
    val stats = statsReceiver.scope("errorFilter")

    (packageInstall
      :+: packageRender
      :+: packageDescribe
      :+: packageSearch
      :+: packageUninstall
      :+: packageListVersions
      :+: packageList
      :+: packageListSources
      :+: packageAddSource
      :+: packageDeleteSource
      :+: capabilities
    )
      .handle {
        case ce: CosmosError =>
          stats.counter(s"definedError/${sanitiseClassName(ce.getClass)}").incr()
          //TODO: should the other portion be a failure as well?
          val output = Ok(Output.failure(ce, ce.status).toResponse[MediaTypes.ErrorResponseType]())
          ce.getHeaders.foldLeft(output) { case (out, kv) => out.withHeader(kv) }
        case fe: io.finch.Error =>
          stats.counter(s"finchError/${sanitiseClassName(fe.getClass)}").incr()
          Ok(Output.failure(fe, Status.BadRequest).toResponse[MediaTypes.ErrorResponseType]())
        case e: Exception if !e.isInstanceOf[io.finch.Error] =>
          stats.counter(s"unhandledException/${sanitiseClassName(e.getClass)}").incr()
          logger.warn("Unhandled exception: ", e)
          Ok(Output.failure(e, Status.InternalServerError).toResponse[MediaTypes.ErrorResponseType]())
        case t: Throwable if !t.isInstanceOf[io.finch.Error] =>
          stats.counter(s"unhandledThrowable/${sanitiseClassName(t.getClass)}").incr()
          logger.warn("Unhandled throwable: ", t)
          Ok(Output.failure(new Exception(t), Status.InternalServerError).toResponse[MediaTypes.ErrorResponseType]())
      }
      .toService
  }

  /**
    * Removes characters from class names that are disallowed by some metrics systems.
    *
    * @param clazz the class whose name is to be santised
    * @return The name of the specified class with all "illegal characters" replaced with '.'
    */
  private[this] def sanitiseClassName(clazz: Class[_]): String = {
    clazz.getName.replaceAllLiterally("$", ".")
  }

}

object Cosmos extends FinchServer {
  def service = {
    implicit val stats = statsReceiver.scope("cosmos")
    import com.netaporter.uri.dsl._

    HttpProxySupport.configureProxySupport()

    val ar = Try(dcosUri())
      .map { dh =>
        val dcosHost: String = Uris.stripTrailingSlash(dh)
        logger.info("Connecting to DCOS Cluster at: {}", dcosHost)
        val adminRouter: Uri = dcosHost
        val mar: Uri = dcosHost / "marathon"
        val master: Uri = dcosHost / "mesos"
        (adminRouter, mar, master)
      }
      .handle {
        case _: IllegalArgumentException =>
          val adminRouter: Uri = adminRouterUri().toStringRaw
          val mar: Uri = marathonUri().toStringRaw
          val master: Uri = mesosMasterUri().toStringRaw
          logger.info("Connecting to Marathon at: {}", mar)
          logger.info("Connecting to Mesos master at: {}", master)
          logger.info("Connection to AdminRouter at: {}", adminRouter)
          (adminRouter, mar, master)
      }
      .flatMap { case (adminRouterUri, marathonUri, mesosMasterUri) =>
        Trys.join(
          Services.adminRouterClient(adminRouterUri).map { adminRouterUri -> _ },
          Services.marathonClient(marathonUri).map { marathonUri -> _ },
          Services.mesosClient(mesosMasterUri).map { mesosMasterUri -> _ }
        )
      }
      .map { case (adminRouter, marathon, mesosMaster) =>
        new AdminRouter(
          new AdminRouterClient(adminRouter._1, adminRouter._2),
          new MarathonClient(marathon._1, marathon._2),
          new MesosMasterClient(mesosMaster._1, mesosMaster._2)
        )
      }

    val boot = ar map { adminRouter =>
      val zkUri = zookeeperUri()
      logger.info("Using {} for the ZooKeeper connection", zkUri)

      val marathonPackageRunner = new MarathonPackageRunner(adminRouter)

      val zkClient = zookeeper.Clients.createAndInitialize(
        zkUri,
        sys.env.get("ZOOKEEPER_USER").zip(sys.env.get("ZOOKEEPER_SECRET")).headOption
      )
      onExit {
        zkClient.close()
      }

      val sourcesStorage = new ZooKeeperStorage(zkClient)()

      val cosmos = Cosmos(adminRouter, marathonPackageRunner, sourcesStorage, UniverseClient(adminRouter))
      cosmos.service
    }
    boot.get
  }

  private[cosmos] def apply(
    adminRouter: AdminRouter,
    packageRunner: PackageRunner,
    sourcesStorage: PackageSourcesStorage,
    universeClient: UniverseClient
  )(implicit statsReceiver: StatsReceiver = NullStatsReceiver): Cosmos = {

    val repositories = new MultiRepository(
      sourcesStorage,
      universeClient
    )

    new Cosmos(
      new UninstallHandler(adminRouter, repositories),
      new PackageInstallHandler(repositories, packageRunner),
      new PackageRenderHandler(repositories),
      new PackageSearchHandler(repositories),
      new PackageDescribeHandler(repositories),
      new ListVersionsHandler(repositories),
      new ListHandler(adminRouter, uri => repositories.getRepository(uri)),
      new PackageRepositoryListHandler(sourcesStorage),
      new PackageRepositoryAddHandler(sourcesStorage),
      new PackageRepositoryDeleteHandler(sourcesStorage),
      new CapabilitiesHandler
    )(statsReceiver)
  }

  private[cosmos] def route[Req, Res,CT<:String](base: Endpoint[HNil], handler: EndpointHandler[Req, Res, CT])(
    requestReader: Endpoint[EndpointContext[Req, Res]]
  ): Endpoint[Response] = {
    (base ? requestReader).apply((context: EndpointContext[Req, Res]) => handler(context))
  }
}
