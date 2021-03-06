/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.connection

import java.io.IOException

/**
 * 通过某个路径进行连接的时候失败了. 可能已经针对多个协议进行了多次尝试，但是都没有成功
 */
class RouteException internal constructor(val firstConnectException: IOException) :
        RuntimeException(firstConnectException) {
    var lastConnectException: IOException = firstConnectException
        private set

    fun addConnectException(e: IOException) {
        firstConnectException.addSuppressed(e)
        lastConnectException = e
    }
}
