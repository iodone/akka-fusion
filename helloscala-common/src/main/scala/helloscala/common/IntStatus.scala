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

package helloscala.common

object IntStatus {
  val ERROR = -1
  val SUCCESS = 0
  val OK = 200
  val CREATE = 201
  val ACCEPTED = 202
  val BAD_REQUEST = 400
  val UNAUTHORIZED = 401
  val NO_CONTENT = 402
  val FORBIDDEN = 403
  val NOT_FOUND = 404
  val NOT_FOUND_CONFIG = 404001
  val CONFLICT = 409
  val INTERNAL_ERROR = 500
  val NOT_IMPLEMENTED = 501
  val BAD_GATEWAY = 502
  val SERVICE_UNAVAILABLE = 503
  val GATEWAY_TIMEOUT = 504
}
