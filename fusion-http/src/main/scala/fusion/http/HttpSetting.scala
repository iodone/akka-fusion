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

import akka.actor.ActorSystem
import com.typesafe.sslconfig.ssl.SSLConfigFactory
import com.typesafe.sslconfig.ssl.SSLConfigSettings
import fusion.common.constant.FusionConstants
import fusion.http.constant.HttpConstants
import helloscala.common.Configuration

class HttpSetting(c: Configuration, system: ActorSystem) {
  def exceptionHandler: String = c.getString("exception-handler")
  def rejectionHandler: String = c.getString("rejection-handler")
  def defaultInterceptor: Seq[String] = c.get[Seq[String]]("default-interceptor")
  def httpInterceptors: Seq[String] = c.get[Seq[String]]("http-interceptors")

//  def http2: UseHttp2 = c.getOrElse("http2", "").toLowerCase match {
//    case "never"  => UseHttp2.Never
//    case "always" => UseHttp2.Always
//    case _        => UseHttp2.Negotiated
//  }

  def createSSLConfig(): SSLConfigSettings = {
    val akkaOverrides = system.settings.config.getConfig("akka.ssl-config")
    val defaults = system.settings.config.getConfig("ssl-config")
    val mergeConfig = akkaOverrides.withFallback(defaults)
    val sslConfig = if (c.hasPath("ssl.ssl-config")) {
      c.getConfig("ssl.ssl-config").withFallback(mergeConfig)
    } else {
      mergeConfig
    }
    SSLConfigFactory.parse(sslConfig)
  }

  object server {

    def host: String =
      c.getOrElse(FusionConstants.SERVER_HOST_PATH, system.settings.config.getString(HttpConstants.SERVER_HOST_PATH))

    def port: Int = {
      c.get[Option[Int]](FusionConstants.SERVER_PORT_PATH) match {
        case Some(port) => port
        case _ =>
          if (!system.settings.config.hasPath(HttpConstants.SERVER_PORT_PATH)) 0
          else system.settings.config.getInt(HttpConstants.SERVER_PORT_PATH)
      }
    }
  }
}
