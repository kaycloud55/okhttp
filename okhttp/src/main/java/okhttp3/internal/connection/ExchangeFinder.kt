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
import java.net.Socket
import okhttp3.Address
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Route
import okhttp3.internal.assertThreadDoesntHoldLock
import okhttp3.internal.assertThreadHoldsLock
import okhttp3.internal.canReuseConnectionFor
import okhttp3.internal.closeQuietly
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.RealInterceptorChain
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException

/**
 * 尝试为这次exchange和随之而来的重试等找到可用的连接。
 *
 * 这个类使用如下的策略：
 *
 *  1. 如果对于当前的call，已经存在连接可以满足响应的request. 那么对于这次exchange和之后的重试重定向等，都使用这个已经存在的connection。
 *
 *  2. 如果对于当前的call，已经存在连接可以满足响应的request. 那么这个已经存在的连接，是有可能被多个指向不同host的request共享的。See
 *     [RealConnection.isEligible] for details.
 *
 *  3. 如果没有合适的连接, 就构造一个routes的list，然后针对这些routes建立新的连接。当请求遇到错误的时候， 就会根据这个route的list去遍历寻找可用的连接。
 *
 * If the pool gains an eligible connection while DNS, TCP, or TLS work is in flight, this finder
 * will prefer pooled connections. Only pooled HTTP/2 connections are used for such de-duplication.
 *
 * 如果正在进行DNS解析、TCP连接、TLS连接的过程中，连接中被添加了一个新的可以被共享的connection，那么这个finder会优先使用连接池中的connection。
 *
 * It is possible to cancel the finding process.
 */
class ExchangeFinder(
        private val connectionPool: RealConnectionPool,
        internal val address: Address, //connection和address关联的。
        private val call: RealCall,
        private val eventListener: EventListener
) {
    private var routeSelection: RouteSelector.Selection? = null

    // State guarded by connectionPool.
    private var routeSelector: RouteSelector? = null
    private var connectingConnection: RealConnection? = null
    private var refusedStreamCount = 0
    private var connectionShutdownCount = 0
    private var otherFailureCount = 0
    private var nextRouteToTry: Route? = null

    /**
     * 寻找当前可用的exchange
     */
    fun find(
            client: OkHttpClient,
            chain: RealInterceptorChain
    ): ExchangeCodec {
        try {

            val resultConnection = findHealthyConnection(
                    connectTimeout = chain.connectTimeoutMillis,
                    readTimeout = chain.readTimeoutMillis,
                    writeTimeout = chain.writeTimeoutMillis,
                    pingIntervalMillis = client.pingIntervalMillis,
                    connectionRetryEnabled = client.retryOnConnectionFailure,
                    doExtensiveHealthChecks = chain.request.method != "GET"
            )
            return resultConnection.newCodec(client, chain) //构造一个IO流的编码器
        } catch (e: RouteException) {
            trackFailure(e.lastConnectException)
            throw e
        } catch (e: IOException) {
            trackFailure(e)
            throw RouteException(e)
        }
    }

    /**
     * 寻找健康的连接. 这个方法会死循环（阻塞），直到找到健康的连接为止。
     */
    @Throws(IOException::class)
    private fun findHealthyConnection(
            connectTimeout: Int,
            readTimeout: Int,
            writeTimeout: Int,
            pingIntervalMillis: Int,
            connectionRetryEnabled: Boolean,
            doExtensiveHealthChecks: Boolean
    ): RealConnection {
        while (true) {
            val candidate = findConnection(
                    connectTimeout = connectTimeout,
                    readTimeout = readTimeout,
                    writeTimeout = writeTimeout,
                    pingIntervalMillis = pingIntervalMillis,
                    connectionRetryEnabled = connectionRetryEnabled
            )

            // 确认当前连接是不是可用的，如果不是就把它从池中剔除，然后继续寻找，直到找到健康的为止。
            if (!candidate.isHealthy(doExtensiveHealthChecks)) {
                candidate.noNewExchanges() //标记为noNewExchanges也就是废弃了，后续连接池会自动回收
                continue
            }

            return candidate
        }
    }

    /**
     * 返回连接以托管新的数据流（exchange）. 会优先从连接池中取已存在的可使用连接
     */
    @Throws(IOException::class)
    private fun findConnection(
            connectTimeout: Int,
            readTimeout: Int,
            writeTimeout: Int,
            pingIntervalMillis: Int,
            connectionRetryEnabled: Boolean
    ): RealConnection {
        var foundPooledConnection = false //在连接池中是不是找到了可用的连接
        var result: RealConnection? = null //找到的或者是新创建的连接
        var selectedRoute: Route? = null //选择的路由策略，请求中可能会配置多个备用的路由策略，比如代理等
        var releasedConnection: RealConnection? //表示这次寻找过程中发现的不可用并被释放了的连接
        val toClose: Socket?
        synchronized(connectionPool) {
            if (call.isCanceled()) throw IOException("Canceled")
            // 1
            releasedConnection = call.connection //直接拿当前call的connection，一般情况下是null的
            toClose = if (call.connection != null &&
                    //不允许在当前连接创建新的exchange（连接已经被废弃了，将连接池回收）或者是和当前url（不是请求同一个地址的）不匹配
                    (call.connection!!.noNewExchanges || !call.connection!!.supportsUrl(address.url))) {
                call.releaseConnectionNoEvents() //会释放这个连接，releasedConnection会跟着变成null
            } else {
                null
            }
            //上面如果满足if条件的话，call.connection已经在realseConnectionNoEvents已经被置为null了
            // 所以能走进去这个条件，说明现在连接可用
            if (call.connection != null) {
                // 经过上面的判断之后，发现call中自带的connection是可用的，也没有连接被释放；所以releasedConnection要置为null
                result = call.connection
                releasedConnection = null
            }
            //经过上面的步骤之后，如果result还为null，说明call.connection不可用被释放了，需要从池中找一个
            if (result == null) {
                // 这个connection没有任何问题。
                refusedStreamCount = 0
                connectionShutdownCount = 0
                otherFailureCount = 0

                // request本身自带的connection不可用，尝试从连接池中寻找一个，并赋值给call.connection
                // 找connection是通过address找的
                if (connectionPool.callAcquirePooledConnection(address, call, null, false)) {
                    foundPooledConnection = true //成功找到一个
                    result = call.connection
                } else if (nextRouteToTry != null) { //尝试下一个route
                    selectedRoute = nextRouteToTry //下一次尝试的路由
                    nextRouteToTry = null
                }
            }
        }
        toClose?.closeQuietly()
        //call.connection == null 或者是 call.connection不为空，但是不可用，就会被释放
        //能走进去这个条件说明call.connection是不可用的，并且被释放了
        if (releasedConnection != null) {
            eventListener.connectionReleased(call, releasedConnection!!)
        }
        //成功的在池中找到了一个可用连接
        if (foundPooledConnection) {
            eventListener.connectionAcquired(call, result!!)
        }
        if (result != null) {
            // 成功找到了一个已经分配好的连接或者是从连接池中取出了一个可用的连接，find成功。
            return result!!
        }

        // If we need a route selection, make one. This is a blocking operation.
        // 上面都没有找到，这里就需要进行路由选择了，。
        var newRouteSelection = false
        if (selectedRoute == null && (routeSelection == null || !routeSelection!!.hasNext())) {
            var localRouteSelector = routeSelector
            if (localRouteSelector == null) {
                localRouteSelector = RouteSelector(address, call.client.routeDatabase, call, eventListener)
                this.routeSelector = localRouteSelector
            }
            newRouteSelection = true
            routeSelection = localRouteSelector.next()
        }

        var routes: List<Route>? = null
        synchronized(connectionPool) {
            if (call.isCanceled()) throw IOException("Canceled")

            if (newRouteSelection) {
                // 现在我们有了一系列的IP地址，所以就依次去尝试。由于会有连接合并，所以依次尝试是有用的。
                routes = routeSelection!!.routes
                // 切换路由继续到连接池中寻找
                if (connectionPool.callAcquirePooledConnection(address, call, routes, false)) {
                    foundPooledConnection = true
                    result = call.connection
                }
            }
            //继续切换寻找
            if (!foundPooledConnection) {
                if (selectedRoute == null) {
                    selectedRoute = routeSelection!!.next()
                }

                // 创建一个新的连接，并且把它分配给这次查找的结果。
                // 异步调用的`cancel()`可能会打断后续的握手操作。
                result = RealConnection(connectionPool, selectedRoute!!) // 2
                connectingConnection = result
            }
        }

        // 在第二个回环的时候找到了合适的connection（也就是切换routes找到了合适的）, we're done.
        // 如果能走进去这里，说明上面的2处操作不会被执行
        if (foundPooledConnection) {
            eventListener.connectionAcquired(call, result!!)
            return result!!
        }

        // 执行TCP和Tls握手操作，这是个阻塞的过程
        result!!.connect(
                connectTimeout,
                readTimeout,
                writeTimeout,
                pingIntervalMillis,
                connectionRetryEnabled,
                call,
                eventListener
        )
        call.client.routeDatabase.connected(result!!.route())

        var socket: Socket? = null
        synchronized(connectionPool) {
            connectingConnection = null
            // 最后尝试一个合并连接，也就是再次尝试当前连接池中有没有新出现可用连接
            // 只有在对一个host并发了多个connection的时候，才能找到。
            if (connectionPool.callAcquirePooledConnection(address, call, routes, true)) {
                // 意思是说我们跑输了，已经有新的可用的连接回到连接池中，我们新创建中得这个连接就要丢掉，使用池中的连接
                result!!.noNewExchanges = true
                socket = result!!.socket()
                result = call.connection

                // 有可能这个时候获取的池中的connection还是unhealthy的，就需要根据刚才成功的route再次尝试
                nextRouteToTry = selectedRoute
            } else {
                //新建的连接放入连接池
                connectionPool.put(result!!)
                call.acquireConnectionNoEvents(result!!)
            }
        }
        socket?.closeQuietly()

        eventListener.connectionAcquired(call, result!!)
        return result!!
    }

    fun connectingConnection(): RealConnection? {
        connectionPool.assertThreadHoldsLock()
        return connectingConnection
    }

    fun trackFailure(e: IOException) {
        connectionPool.assertThreadDoesntHoldLock()

        synchronized(connectionPool) {
            nextRouteToTry = null
            if (e is StreamResetException && e.errorCode == ErrorCode.REFUSED_STREAM) {
                refusedStreamCount++
            } else if (e is ConnectionShutdownException) {
                connectionShutdownCount++
            } else {
                otherFailureCount++
            }
        }
    }

    /**
     * Returns true if the current route has a failure that retrying could fix, and that there's
     * a route to retry on.
     */
    fun retryAfterFailure(): Boolean {
        synchronized(connectionPool) {
            if (refusedStreamCount == 0 && connectionShutdownCount == 0 && otherFailureCount == 0) {
                return false // Nothing to recover from.
            }

            if (nextRouteToTry != null) {
                return true
            }

            if (retryCurrentRoute()) {
                // Lock in the route because retryCurrentRoute() is racy and we don't want to call it twice.
                nextRouteToTry = call.connection!!.route()
                return true
            }

            // If we have a routes left, use 'em.
            if (routeSelection?.hasNext() == true) return true

            // If we haven't initialized the route selector yet, assume it'll have at least one route.
            val localRouteSelector = routeSelector ?: return true

            // If we do have a route selector, use its routes.
            return localRouteSelector.hasNext()
        }
    }

    /**
     * Return true if the route used for the current connection should be retried, even if the
     * connection itself is unhealthy. The biggest gotcha here is that we shouldn't reuse routes from
     * coalesced connections.
     */
    private fun retryCurrentRoute(): Boolean {
        if (refusedStreamCount > 1 || connectionShutdownCount > 1 || otherFailureCount > 0) {
            return false // This route has too many problems to retry.
        }

        val connection = call.connection
        return connection != null &&
                connection.routeFailureCount == 0 &&
                connection.route().address.url.canReuseConnectionFor(address.url)
    }
}
