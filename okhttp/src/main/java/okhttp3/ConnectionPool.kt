/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3

import java.util.concurrent.TimeUnit
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.connection.RealConnectionPool

/**
 * 管理HTTP 和 HTTP/2的连接重用，减少网络延迟。
 * 共享[Address]的HTTP请求会共享一个[Connection]
 * This class implements the policy of which connections to keep open for future use.
 *
 * @constructor Create a new connection pool with tuning parameters appropriate for a single-user
 * application. The tuning parameters in this pool are subject to change in future OkHttp releases.
 * Currently this pool holds up to 5 idle connections which will be evicted after 5 minutes of
 * inactivity.
 * 在当前版本的连接池管理，最多保持5个空闲的连接，如果超过5分钟没有被使用，就会被释放。
 */
class ConnectionPool internal constructor(
    internal val delegate: RealConnectionPool
) {
    constructor(
        maxIdleConnections: Int,
        keepAliveDuration: Long,
        timeUnit: TimeUnit
    ) : this(RealConnectionPool(
        taskRunner = TaskRunner.INSTANCE,
        maxIdleConnections = maxIdleConnections,
        keepAliveDuration = keepAliveDuration,
        timeUnit = timeUnit
    ))

    constructor() : this(5, 5, TimeUnit.MINUTES)

    /** Returns the number of idle connections in the pool. */
    fun idleConnectionCount(): Int = delegate.idleConnectionCount()

    /** Returns total number of connections in the pool. */
    fun connectionCount(): Int = delegate.connectionCount()

    /** Close and remove all idle connections in the pool. */
    fun evictAll() {
        delegate.evictAll()
    }
}
