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
 * 连接池。
 * 管理HTTP 和 HTTP/2的连接重用，减少网络延迟。
 * 共享[Address]的HTTP请求会共享一个[Connection]
 * This class implements the policy of which connections to keep open for future use.
 *
 * 这个类实现了每个连接保持open。
 *
 * 在当前版本的连接池管理，最多保持5个空闲的连接，如果超过5分钟没有被使用，就会被释放。
 *
 * 这里其实和线程池类似，每个连接对一个线程，对于IDLE的链接，都会有超时回收时间。
 */
class ConnectionPool internal constructor(
        internal val delegate: RealConnectionPool
) {
    constructor(
            maxIdleConnections: Int, //允许的最大空闲连接
            keepAliveDuration: Long, //keepAlive的时间
            timeUnit: TimeUnit //keepAlive的单位
    ) : this(RealConnectionPool(
            taskRunner = TaskRunner.INSTANCE,
            maxIdleConnections = maxIdleConnections,
            keepAliveDuration = keepAliveDuration,
            timeUnit = timeUnit
    ))

    constructor() : this(5, 5, TimeUnit.MINUTES)

    fun idleConnectionCount(): Int = delegate.idleConnectionCount()

    fun connectionCount(): Int = delegate.connectionCount()

    fun evictAll() {
        delegate.evictAll()
    }
}
