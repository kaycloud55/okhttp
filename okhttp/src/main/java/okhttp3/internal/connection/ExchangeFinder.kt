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
        internal val address: Address,
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
            return resultConnection.newCodec(client, chain)
        } catch (e: RouteException) {
            trackFailure(e.lastConnectException)
            throw e
        } catch (e: IOException) {
            trackFailure(e)
            throw RouteException(e)
        }
    }

    /**
     * 寻找健康的连接. 这个方法会死循环，知道找到健康的连接为止
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

            // Confirm that the connection is good. If it isn't, take it out of the pool and start again.
            if (!candidate.isHealthy(doExtensiveHealthChecks)) {
                candidate.noNewExchanges()
                continue
            }

            return candidate
        }
    }

    /**
     * 返回连接以托管新的数据流. 会优先从连接池中取已存在的可使用连接
     */
    @Throws(IOException::class)
    private fun findConnection(
            connectTimeout: Int,
            readTimeout: Int,
            writeTimeout: Int,
            pingIntervalMillis: Int,
            connectionRetryEnabled: Boolean
    ): RealConnection {
        var foundPooledConnection = false
        var result: RealConnection? = null
        var selectedRoute: Route? = null
        var releasedConnection: RealConnection?
        val toClose: Socket?
        synchronized(connectionPool) {
            if (call.isCanceled()) throw IOException("Canceled")

            releasedConnection = call.connection
            toClose = if (call.connection != null &&
                    //不允许在当前连接创建新的exchange或者是和当前url不匹配
                    (call.connection!!.noNewExchanges || !call.connection!!.supportsUrl(address.url))) {
                call.releaseConnectionNoEvents()
            } else {
                null
            }

            if (call.connection != null) {
                // 已经有了一个分配好的connection，并且是可用的
                result = call.connection
                releasedConnection = null
            }

            if (result == null) {
                // The connection hasn't had any problems for this call.
                refusedStreamCount = 0
                connectionShutdownCount = 0
                otherFailureCount = 0

                // request本身自带的connection不可用，尝试从连接池中寻找一个
                if (connectionPool.callAcquirePooledConnection(address, call, null, false)) {
                    foundPooledConnection = true
                    result = call.connection
                } else if (nextRouteToTry != null) {
                    selectedRoute = nextRouteToTry
                    nextRouteToTry = null
                }
            }
        }
        toClose?.closeQuietly()

        if (releasedConnection != null) {
            eventListener.connectionReleased(call, releasedConnection!!)
        }
        if (foundPooledConnection) {
            eventListener.connectionAcquired(call, result!!)
        }
        if (result != null) {
            // If we found an already-allocated or pooled connection, we're done.
            return result!!
        }

        // If we need a route selection, make one. This is a blocking operation.
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
                // Now that we have a set of IP addresses, make another attempt at getting a connection from
                // the pool. This could match due to connection coalescing.
                routes = routeSelection!!.routes
                if (connectionPool.callAcquirePooledConnection(address, call, routes, false)) {
                    foundPooledConnection = true
                    result = call.connection
                }
            }

            if (!foundPooledConnection) {
                if (selectedRoute == null) {
                    selectedRoute = routeSelection!!.next()
                }

                // Create a connection and assign it to this allocation immediately. This makes it possible
                // for an asynchronous cancel() to interrupt the handshake we're about to do.
                result = RealConnection(connectionPool, selectedRoute!!)
                connectingConnection = result
            }
        }

        // If we found a pooled connection on the 2nd time around, we're done.
        if (foundPooledConnection) {
            eventListener.connectionAcquired(call, result!!)
            return result!!
        }

        // Do TCP + TLS handshakes. This is a blocking operation.
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
            // Last attempt at connection coalescing, which only occurs if we attempted multiple
            // concurrent connections to the same host.
            if (connectionPool.callAcquirePooledConnection(address, call, routes, true)) {
                // We lost the race! Close the connection we created and return the pooled connection.
                result!!.noNewExchanges = true
                socket = result!!.socket()
                result = call.connection

                // It's possible for us to obtain a coalesced connection that is immediately unhealthy. In
                // that case we will retry the route we just successfully connected with.
                nextRouteToTry = selectedRoute
            } else {
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
