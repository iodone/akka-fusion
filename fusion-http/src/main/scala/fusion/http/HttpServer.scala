/*
 * Copyright 2019 helloscala.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fusion.http

import java.net.InetSocketAddress
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean

import akka.Done
import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.http.FusionRoute
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.ConnectionContext
import akka.http.scaladsl.Http
import akka.http.scaladsl.HttpConnectionContext
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.server.ExceptionHandler
import akka.http.scaladsl.server.RejectionHandler
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import fusion.core.event.http.HttpBindingServerEvent
import fusion.core.extension.FusionCore
import fusion.http.interceptor.HttpInterceptor
import fusion.http.server.AbstractRoute
import fusion.http.util.HttpUtils
import helloscala.common.Configuration
import helloscala.common.exception.HSInternalErrorException
import helloscala.common.util.NetworkUtils

import scala.concurrent.Await
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

final class HttpServer(val id: String, val system: ExtendedActorSystem) extends StrictLogging with AutoCloseable {
  implicit private def _system: ActorSystem = system
  implicit private val mat: ActorMaterializer = ActorMaterializer()
  implicit private def ec: ExecutionContextExecutor = mat.executionContext
  private val _isStarted = new AtomicBoolean(false)
  @volatile private var _isRunning = false
  private val c = Configuration(system.settings.config.getConfig(id))
  private var _socketAddress: InetSocketAddress = _
  private var maybeEventualBinding = Option.empty[Future[ServerBinding]]
  private val httpSetting = new HttpSetting(c, system)

  @throws(classOf[Exception])
  def startHandlerSync(handler: HttpHandler)(
      implicit
      rejectionHandler: RejectionHandler = createRejectionHandler(),
      exceptionHandler: ExceptionHandler = createExceptionHandler(),
      duration: Duration = 30.seconds): ServerBinding =
    Await.result(startHandlerAsync(handler), duration)

  def startHandlerAsync(handler: HttpHandler)(
      implicit rejectionHandler: RejectionHandler = createRejectionHandler(),
      exceptionHandler: ExceptionHandler = createExceptionHandler()): Future[ServerBinding] = {
    import akka.http.scaladsl.server.Directives._
    val route = extractRequest { request =>
      complete(handler(request))
    }
    startRouteAsync(route)
  }

  def startAbstractRouteSync(route: AbstractRoute)(
      implicit
      rejectionHandler: RejectionHandler = createRejectionHandler(),
      exceptionHandler: ExceptionHandler = createExceptionHandler(),
      duration: Duration = 30.seconds): ServerBinding = {
    Await.result(startRouteAsync(route.route), duration)
  }

  @throws(classOf[Exception])
  def startRouteSync(route: Route)(
      implicit
      rejectionHandler: RejectionHandler = createRejectionHandler(),
      exceptionHandler: ExceptionHandler = createExceptionHandler(),
      duration: Duration = 30.seconds): ServerBinding = {
    Await.result(startRouteAsync(route), duration)
  }

  def startRouteAsync(_route: Route)(
      implicit rejectionHandler: RejectionHandler = createRejectionHandler(),
      exceptionHandler: ExceptionHandler = createExceptionHandler()): Future[ServerBinding] = {
    if (!_isStarted.compareAndSet(false, true)) {
      throw HSInternalErrorException("HttpServer只允许start一次")
    }

    val connectionContext = generateConnectionContext()

    val handler = toHandler(Route.seal(_route))
    val bindingFuture =
      Http().bindAndHandle(handler, httpSetting.server.host, httpSetting.server.port, connectionContext)

    maybeEventualBinding = Some(bindingFuture)
    bindingFuture.failed.foreach { cause =>
      afterHttpBindingFailure(cause, connectionContext.isSecure)
    }
    bindingFuture.map { binding =>
      afterHttpBindingSuccess(binding, connectionContext.isSecure)
      binding
    }
  }

  private def toHandler(_route: Route): Flow[HttpRequest, HttpResponse, NotUsed] = {
    var route = _route
    route =
      (getDefaultInterceptor() ++ getHttpInterceptors()).reverse.foldLeft(route)((route, i) => i.interceptor(route))
    Flow[HttpRequest].mapAsync(1)(FusionRoute.asyncHandler(route))
  }

  private def getDefaultInterceptor(): Seq[HttpInterceptor] =
    httpSetting.defaultInterceptor.flatMap(createHttpInterceptor)

  private def getHttpInterceptors(): Seq[HttpInterceptor] = {
    httpSetting.httpInterceptors.flatMap(className => createHttpInterceptor(className))
  }

  private def createRejectionHandler(): RejectionHandler = {
    val clz = Class.forName(httpSetting.rejectionHandler)
    val either = system.dynamicAccess
      .createInstanceFor[RejectionHandler](clz, List(classOf[ExtendedActorSystem] -> system))
      .orElse(system.dynamicAccess.createInstanceFor[RejectionHandler](clz, List(classOf[ActorSystem] -> system)))
      .orElse(system.dynamicAccess.createInstanceFor[RejectionHandler](clz, Nil))
    either match {
      case Success(rejectionHandler) => rejectionHandler
      case Failure(e) =>
        throw new ExceptionInInitializerError(
          s"$clz 不是有效的 akka.http.scaladsl.server.RejectionHandler，${e.getLocalizedMessage}")
    }
  }

  private def createExceptionHandler(): ExceptionHandler = {
    val clz = Class.forName(httpSetting.exceptionHandler)
    val either = system.dynamicAccess
      .createInstanceFor[ExceptionHandler.PF](clz, List(classOf[ExtendedActorSystem] -> system))
      .orElse(system.dynamicAccess.createInstanceFor[ExceptionHandler.PF](clz, List(classOf[ActorSystem] -> system)))
      .orElse(system.dynamicAccess.createInstanceFor[ExceptionHandler.PF](clz, Nil))
    either match {
      case Success(pf) => ExceptionHandler(pf)
      case Failure(e) =>
        throw new ExceptionInInitializerError(
          s"$clz 不是有效的 akka.http.scaladsl.server.ExceptionHandler.PF，${e.getLocalizedMessage}")
    }
  }

  private def createHttpInterceptor(className: String): Option[HttpInterceptor] = {
    val clz = Class.forName(className)
    val triedInterceptor = system.dynamicAccess
      .createInstanceFor[HttpInterceptor](clz, List(classOf[ExtendedActorSystem] -> system))
      .orElse(system.dynamicAccess.createInstanceFor[HttpInterceptor](clz, List(classOf[ActorSystem] -> system)))
      .orElse(system.dynamicAccess.createInstanceFor[HttpInterceptor](clz, Nil))
    triedInterceptor match {
      case Success(v) => Some(v)
      case Failure(e) =>
        logger.error(s"实例化 HttpFilter 错误：$clz 不是有效的 ${classOf[HttpInterceptor]}", e)
        None
    }
  }

  private def generateConnectionContext(): ConnectionContext = {
    if (!c.hasPath("ssl")) {
      HttpConnectionContext()
    } else {
      val akkaSslConfig = new AkkaSSLConfig(system, httpSetting.createSSLConfig())
      val keyPassword = c.getString("ssl.key-store.password")
      val keyPath = c.getString("ssl.key-store.path")
      val keystore =
        Objects.requireNonNull(getClass.getClassLoader.getResourceAsStream(keyPath), s"keystore不能为空，keyPath: $keyPath")
      val keyStoreType = c.getOrElse("ssl.key-store.type", "PKCS12")
      val algorithm = c.getOrElse("ssl.key-store.algorithm", "SunX509")
      val protocol = c.getOrElse("ssl.protocol", akkaSslConfig.config.protocol)
      HttpUtils.generateHttps(keyPassword, keystore, keyStoreType, algorithm, protocol, Some(akkaSslConfig))
    }
  }

  def isStarted(): Boolean = _isStarted.get()

  def isRunning(): Boolean = _isRunning

  private def _saveServer(socketAddress: InetSocketAddress): InetSocketAddress = {
    val inetAddress = socketAddress.getAddress match {
      case address if Objects.isNull(address) || address.getAddress.apply(0) == 0 =>
        logger.info(s"绑定地址为所有地址：$address，尝试查找本机第一个有效网络地址")
        NetworkUtils
          .firstOnlineInet4Address()
          .map { add =>
            logger.info(s"找到本机第一个有效网络地址：$add")
            add
          }
          .getOrElse(socketAddress.getAddress)
      case address => address
    }
    val host = inetAddress.getHostAddress
    val port = socketAddress.getPort
    _socketAddress = new InetSocketAddress(inetAddress, port)
    System.setProperty(s"$id.server.host", host)
    System.setProperty(s"$id.server.port", port.toString)
    ConfigFactory.invalidateCaches()
    _socketAddress
  }

  private def afterHttpBindingSuccess(binding: ServerBinding, isSecure: Boolean): Unit = {
    val schema = if (isSecure) "https" else "http"
    val socketAddress = _saveServer(binding.localAddress)
    logger.info(s"Server online at $schema://${socketAddress.getHostString}:${socketAddress.getPort}")
    _isRunning = true
    FusionCore(system).events.http.complete(HttpBindingServerEvent(Success(socketAddress), isSecure))
  }

  private def afterHttpBindingFailure(cause: Throwable, isSecure: Boolean): Unit = {
    val schema = if (isSecure) "https" else "http"
    logger.error(s"Error starting the $schema server ${cause.getMessage}", cause)
    close()
    FusionCore(system).events.http.complete(HttpBindingServerEvent(Failure(cause), isSecure))
  }

  /**
   * close后不能调用 [[startHandlerAsync()]]或[[startRouteAsync()]]重新启动，需要再次构造实例运行
   */
  override def close(): Unit = {
    closeAsync()
  }

  def closeAsync(): Future[Done] = {
    val future = maybeEventualBinding match {
      case Some(binding) => binding.flatMap(_.unbind())
      case _             => Future.successful(Done)
    }
    future.map { done =>
      maybeEventualBinding = None
      _isStarted.set(false)
      _isRunning = false
      done
    }
  }

  def socketAddress: InetSocketAddress = _socketAddress
}
