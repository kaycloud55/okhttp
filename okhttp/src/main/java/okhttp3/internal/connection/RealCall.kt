/*
 * Copyright (C) 2014 Square, Inc.
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
import java.io.InterruptedIOException
import java.lang.ref.WeakReference
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import okhttp3.Address
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.assertThreadDoesntHoldLock
import okhttp3.internal.assertThreadHoldsLock
import okhttp3.internal.cache.CacheInterceptor
import okhttp3.internal.closeQuietly
import okhttp3.internal.http.BridgeInterceptor
import okhttp3.internal.http.CallServerInterceptor
import okhttp3.internal.http.RealInterceptorChain
import okhttp3.internal.http.RetryAndFollowUpInterceptor
import okhttp3.internal.platform.Platform
import okhttp3.internal.threadName
import okio.AsyncTimeout

/**
 * HTTP应用层和网络层之间的桥梁。这个类暴露了高层次的应用层概念：连接、请求、响应、流
 *
 * Call可以被异步取消。在HTTP/2中，取消请求会取消当前的流，但是不会影响其他的流，整个连接不会受影响。
 * 如果是还在SSL握手过程中的话，可能会中断连接的建立。
 */
class RealCall(
        val client: OkHttpClient,
        val originalRequest: Request, //也就是开发者通过new Call创建的
        val forWebSocket: Boolean
) : Call {
    private val connectionPool: RealConnectionPool = client.connectionPool.delegate //这里有委托的设计思想

    private val eventListener: EventListener = client.eventListenerFactory.create(this) //请求监听

    private val timeout = object : AsyncTimeout() {
        override fun timedOut() {
            cancel() //超时取消
        }
    }.apply {
        timeout(client.callTimeoutMillis.toLong(), MILLISECONDS)
    }

    /** 在[callStart]方法中初始化. 调用栈，用来跟踪问题 */
    private var callStackTrace: Any? = null

    /** Finds an exchange to send the next request and receive the next response. */
    private var exchangeFinder: ExchangeFinder? = null

    // 由connectionPool进行管理
    var connection: RealConnection? = null //执行call的连接
    private var exchange: Exchange? = null //一次数据交换过程的抽象
    private var exchangeRequestDone = false //request发送完成
    private var exchangeResponseDone = false //response接收完成
    private var canceled = false
    private var timeoutEarlyExit = false //超时提前退出
    private var noMoreExchanges = false

    // Guarded by this.
    private var executed = false

    /**
     * This is the same value as [exchange], but scoped to the execution of the network interceptors.
     * The [exchange] field is assigned to null when its streams end, which may be before or after the
     * network interceptors return.
     */
    internal var interceptorScopedExchange: Exchange? = null
        private set

    override fun timeout() = timeout

    @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
    override fun clone() = RealCall(client, originalRequest, forWebSocket)

    override fun request(): Request = originalRequest

    /**
     * 立即关闭socket连接。
     * Use this to interrupt an in-flight request from any thread. It's the caller's responsibility to close the
     * request body and response body streams; otherwise resources may be leaked.
     *
     * This method is safe to be called concurrently, but provides limited guarantees. If a transport
     * layer connection has been established (such as a HTTP/2 stream) that is terminated. Otherwise
     * if a socket connection is being established, that is terminated.
     */
    override fun cancel() {
        val exchangeToCancel: Exchange?
        val connectionToCancel: RealConnection?
        synchronized(connectionPool) {
            if (canceled) return // Already canceled.
            canceled = true
            exchangeToCancel = exchange
            connectionToCancel = exchangeFinder?.connectingConnection() ?: connection
        }
        exchangeToCancel?.cancel() ?: connectionToCancel?.cancel() //exchangeToCancel ==
        // null表示还没到数据交换的这一步，所以直接取消连接，关闭socket连接
        eventListener.canceled(this) //通知给调用方
    }

    override fun isCanceled(): Boolean {
        synchronized(connectionPool) {
            return canceled
        }
    }

    override fun execute(): Response {
        synchronized(this) {
            check(!executed) { "Already Executed" }
            executed = true
        }
        //开始timeout倒计时
        timeout.enter()
        callStart() //回调eventListener的callStart
        try {
            client.dispatcher.executed(this) // 添加到runningSyncCalls中，这一步是没有执行实质性操作的。只是起一个标记的作用。
            return getResponseWithInterceptorChain() //通过责任链获取响应结果，真正执行请求就是通过它来操作的
        } finally {
            client.dispatcher.finished(this) // 通知请求完成，把当前call从runningSyncCalls中移除；不管是success还是error，都是要finish的。
        }
    }

    override fun enqueue(responseCallback: Callback) {
        //会对当前call加锁
        synchronized(this) {
            check(!executed) { "Already Executed" }
            executed = true
        }
        callStart() //回调通知
        client.dispatcher.enqueue(AsyncCall(responseCallback))
    }

    @Synchronized
    override fun isExecuted(): Boolean = executed

    private fun callStart() {
        this.callStackTrace = Platform.get().getStackTraceForCloseable("response.body().close()")
        eventListener.callStart(this)
    }

    @Throws(IOException::class)
    internal fun getResponseWithInterceptorChain(): Response {
        // 组装interceptors.
        // 这里其实可以明显看到interceptor和network interceptor，本质上它们都是拦截器，区别也只是实现的区别而已。
        // 而network interceptor的所谓可以多次调用，也是只因为它们在retryAndFollowUpInterceptor的底层的原因，因为重试和重定向
        // 会把请求重新发一遍，response被拦截，request被重新发起一次，会再次走到下面的这些network interceptor，也就是导致它们被多次调用。
        val interceptors = mutableListOf<Interceptor>()
        interceptors += client.interceptors //所有用户自定义的全程interceptor，拼接在最前面
        interceptors += RetryAndFollowUpInterceptor(client)
        interceptors += BridgeInterceptor(client.cookieJar) //添加一些header
        interceptors += CacheInterceptor(client.cache)
        interceptors += ConnectInterceptor //建立连接；cache在它之前，因为如果cache命中就不用建立连接了，可以节约成本。
        //webSocket禁用networkInterceptor
        if (!forWebSocket) {
            interceptors += client.networkInterceptors //所有用户自定义的network interceptor，拼接在最后面
        }
        interceptors += CallServerInterceptor(forWebSocket) //真正发送请求的
        //责任链模式，创建Interceptor链
        val chain = RealInterceptorChain(
                call = this,
                interceptors = interceptors,
                index = 0,
                exchange = null, //exchange这里是还没有建构的
                request = originalRequest,
                connectTimeoutMillis = client.connectTimeoutMillis,
                readTimeoutMillis = client.readTimeoutMillis,
                writeTimeoutMillis = client.writeTimeoutMillis
        )

        var calledNoMoreExchanges = false
        try {
            //启动责任链，注意，在上面构造责任链的时候初始index是0，也就是从第一个interceptor开始执行
            val response = chain.proceed(originalRequest)
            //这里类似于一个递-归的过程：request经过一层层的interceptor处理，一层层往下传递，最后callServerInterceptor和服务器交互完之后又把response
            // 一层层往回传递，最后得到这个返回的response

            //如果在处理的过程中调用了[call.cancel]，会直接把连接断开，response可能还没被填充，也可能已经被响应填充了，但是都应该关闭
            //但是这里不会导致crash，因为在execute和enque这两个调用方都做了try catch;这里本身也有一层try catch
            if (isCanceled()) {
                response.closeQuietly()
                throw IOException("Canceled")
            }
            return response
        } catch (e: IOException) {
            calledNoMoreExchanges = true //主要是给下面的finally代码标记，进行分支选择
            throw noMoreExchanges(e) as Throwable
        } finally {
            if (!calledNoMoreExchanges) {
                //上面的调用没有抛异常才会走到这里来，也就是请求成功了
                noMoreExchanges(null)
            }
        }
    }

    /**
     *
     * 为后面发生在network interceptor中的数据交换做准备。这个方法主要是为request构建一个exchange。
     *
     * 所谓的exchange其实也就是和服务端发生的一次数据交互，OKHTTP把这个过程抽象为了一个类。如果是缓存命中的话，就不需要exchange。
     *
     * @param newExchangeFinder 如果这不是一次重试，并且route是可用的，就返回true。
     */
    fun enterNetworkInterceptorExchange(request: Request, newExchangeFinder: Boolean) {
        check(interceptorScopedExchange == null)
        check(exchange == null) {
            "cannot make a new request because the previous response is still open: " +
                    "please call response.close()"
        }

        if (newExchangeFinder) {
            this.exchangeFinder = ExchangeFinder(
                    connectionPool,
                    createAddress(request.url),
                    this,
                    eventListener
            )
        }
    }

    /**
     *
     * Exchange负责真正的发送和接收请求，和服务端交互。
     * 这个方法在ConnectInterceptor中调用，用来初始化当前call的exchange。
     *
     * */
    internal fun initExchange(chain: RealInterceptorChain): Exchange {
        synchronized(connectionPool) {
            check(!noMoreExchanges) { "released" } //必须还能进行数据交换
            check(exchange == null) //exchange必须等于null，也就是没被初始化过
        }

        val codec = exchangeFinder!!.find(client, chain) //这里内部会进行连接的建立
        val result = Exchange(this, eventListener, exchangeFinder!!, codec)
        this.interceptorScopedExchange = result

        synchronized(connectionPool) {
            this.exchange = result
            this.exchangeRequestDone = false
            this.exchangeResponseDone = false
            return result
        }
    }

    /**
     * 这个方法由exchangeFinder调用，在初始化exchange之前，先初始化connection
     */
    fun acquireConnectionNoEvents(connection: RealConnection) {
        connectionPool.assertThreadHoldsLock() //必须先获得锁

        check(this.connection == null) //连接必须为null才能往下走
        this.connection = connection
        connection.calls.add(CallReference(this, callStackTrace)) //加入到当前的连接的call中，用弱引用来维持connection
    }

    /**
     * 释放被[exchange]的request和response持有的资源.
     *
     * 这个方法在request完成或者抛出异常的时候被调用。
     *
     * 如果exchange被取消或者是timeout了，这个方法会接收这个exception
     */
    internal fun <E : IOException?> messageDone(
            exchange: Exchange,
            requestDone: Boolean,
            responseDone: Boolean,
            e: E
    ): E {
        var result = e
        var exchangeDone = false
        synchronized(connectionPool) {
            if (exchange != this.exchange) {
                return result // This exchange was detached violently!
            }
            var changed = false
            if (requestDone) {
                if (!exchangeRequestDone) changed = true
                this.exchangeRequestDone = true
            }
            if (responseDone) {
                if (!exchangeResponseDone) changed = true
                this.exchangeResponseDone = true
            }
            if (exchangeRequestDone && exchangeResponseDone && changed) {
                exchangeDone = true
                this.exchange!!.connection.successCount++
                this.exchange = null
            }
        }
        if (exchangeDone) {
            result = maybeReleaseConnection(result, false)
        }
        return result
    }

    /**
     * 当前call不会再有数据交换了，表示已经被执行过了（可能成功了，也可能抛出了异常）
     */
    internal fun noMoreExchanges(e: IOException?): IOException? {
        synchronized(connectionPool) {
            noMoreExchanges = true
        }
        return maybeReleaseConnection(e, false) //考虑释放连接
    }

    /**
     * 不再使用的时候释放connection.
     * 在每次交换完成之后，call会通知没有新的交换[Exchange]需要进行，就会调用这个方法。
     *
     * If the call was canceled or timed out, this will wrap [e] in an exception that provides that
     * additional context. Otherwise [e] is returned as-is.
     *
     * @param force true to release the connection even if more exchanges are expected for the call.
     */
    private fun <E : IOException?> maybeReleaseConnection(e: E, force: Boolean): E {
        var result = e
        val socket: Socket?
        var releasedConnection: Connection?
        val callEnd: Boolean
        synchronized(connectionPool) {
            //必须要满足force == false或者是exchange == null，才能继续往下走
            // 1.force == false表示不需要强制释放，所以即使exchange != null也可以继续往下走，因为它不一定会执行释放连接的操作。
            check(!force || exchange == null) { "cannot release connection while it is in use" }
            releasedConnection = this.connection
            socket = if (this.connection != null && exchange == null && (force || noMoreExchanges)) {
                releaseConnectionNoEvents() //释放连接
            } else {
                null
            }
            if (this.connection != null) releasedConnection = null //并没有释放成功
            callEnd = noMoreExchanges && exchange == null
        }
        socket?.closeQuietly()

        if (releasedConnection != null) {
            eventListener.connectionReleased(this, releasedConnection!!) //通知调用方
        }

        if (callEnd) {
            val callFailed = result != null
            result = timeoutExit(result)
            if (callFailed) {
                eventListener.callFailed(this, result!!)
            } else {
                eventListener.callEnd(this)
            }
        }
        return result
    }

    /**
     * 从connection的allocation列表中移除当前call，并且返回当前connection的socket引用，给外部调用close
     *
     */
    internal fun releaseConnectionNoEvents(): Socket? {
        connectionPool.assertThreadHoldsLock()

        val index = connection!!.calls.indexOfFirst { it.get() == this@RealCall }
        check(index != -1)

        val released = this.connection
        released!!.calls.removeAt(index) //从连接的call列表中移除当前请求
        this.connection = null //help GC
        //没有数据交换了，关闭socket连接
        if (released.calls.isEmpty()) {
            released.idleAtNs = System.nanoTime()
            if (connectionPool.connectionBecameIdle(released)) {
                return released.socket()
            }
        }

        return null
    }

    private fun <E : IOException?> timeoutExit(cause: E): E {
        if (timeoutEarlyExit) return cause
        if (!timeout.exit()) return cause

        val e = InterruptedIOException("timeout")
        if (cause != null) e.initCause(cause)
        @Suppress("UNCHECKED_CAST") // E is either IOException or IOException?
        return e as E
    }

    /**
     * Stops applying the timeout before the call is entirely complete. This is used for WebSockets
     * and duplex calls where the timeout only applies to the initial setup.
     */
    fun timeoutEarlyExit() {
        check(!timeoutEarlyExit)
        timeoutEarlyExit = true
        timeout.exit()
    }

    /**
     * @param closeExchange true if the current exchange should be closed because it will not be used.
     *     This is usually due to either an exception or a retry.
     */
    internal fun exitNetworkInterceptorExchange(closeExchange: Boolean) {
        check(!noMoreExchanges) { "released" }

        if (closeExchange) {
            exchange?.detachWithViolence()
            check(exchange == null)
        }

        interceptorScopedExchange = null
    }

    /**
     * 1.请求创建的时候就需要初始化对应的Address，因为需要通过Address来建立connection
     *   这个方法在创建ExchangeFinder的时候调用。
     *
     *   Address所需要的参数由URL和OkHttpClient提供。
     */
    private fun createAddress(url: HttpUrl): Address {
        var sslSocketFactory: SSLSocketFactory? = null
        var hostnameVerifier: HostnameVerifier? = null
        var certificatePinner: CertificatePinner? = null
        if (url.isHttps) {
            sslSocketFactory = client.sslSocketFactory
            hostnameVerifier = client.hostnameVerifier
            certificatePinner = client.certificatePinner
        }

        return Address(
                uriHost = url.host,
                uriPort = url.port,
                dns = client.dns,
                socketFactory = client.socketFactory,
                sslSocketFactory = sslSocketFactory,
                hostnameVerifier = hostnameVerifier,
                certificatePinner = certificatePinner,
                proxyAuthenticator = client.proxyAuthenticator,
                proxy = client.proxy,
                protocols = client.protocols,
                connectionSpecs = client.connectionSpecs,
                proxySelector = client.proxySelector
        )
    }

    fun retryAfterFailure() = exchangeFinder!!.retryAfterFailure()

    /**
     * Returns a string that describes this call. Doesn't include a full URL as that might contain
     * sensitive information.
     */
    private fun toLoggableString(): String {
        return ((if (isCanceled()) "canceled " else "") +
                (if (forWebSocket) "web socket" else "call") +
                " to " + redactedUrl())
    }

    internal fun redactedUrl(): String = originalRequest.url.redact()

    /**
     * 异步执行的Call
     */
    internal inner class AsyncCall(
            private val responseCallback: Callback
    ) : Runnable {
        @Volatile
        var callsPerHost = AtomicInteger(0) //每台主机并发的call数量
            private set

        /**
         * 复用已经存在的call
         * @param other 已经存在的Call
         */
        fun reuseCallsPerHostFrom(other: AsyncCall) {
            this.callsPerHost = other.callsPerHost
        }

        val host: String
            get() = originalRequest.url.host

        val request: Request
            get() = originalRequest

        val call: RealCall
            get() = this@RealCall

        /**
         * Attempt to enqueue this async call on [executorService]. This will attempt to clean up
         * if the executor has been shut down by reporting the call as failed.
         */
        fun executeOn(executorService: ExecutorService) {
            client.dispatcher.assertThreadDoesntHoldLock()

            var success = false
            try {
                executorService.execute(this) //线程池的调度方法，执行run()方法
                success = true
            } catch (e: RejectedExecutionException) {
                val ioException = InterruptedIOException("executor rejected")
                ioException.initCause(e)
                noMoreExchanges(ioException)
                responseCallback.onFailure(this@RealCall, ioException)
            } finally {
                if (!success) {
                    client.dispatcher.finished(this) // This call is no longer running!
                }
            }
        }

        override fun run() {
            //暂时修改线程名
            threadName("OkHttp ${redactedUrl()}") {
                var signalledCallback = false
                timeout.enter() //开始计算timeout时间
                try {
                    val response = getResponseWithInterceptorChain() //开始请求的入口
                    signalledCallback = true //开始计算timeout
                    responseCallback.onResponse(this@RealCall, response)
                } catch (e: IOException) {
                    if (signalledCallback) {
                        // Do not signal the callback twice!
                        Platform.get().log("Callback failure for ${toLoggableString()}", Platform.INFO, e)
                    } else {
                        responseCallback.onFailure(this@RealCall, e)
                    }
                } catch (t: Throwable) {
                    cancel()
                    if (!signalledCallback) {
                        val canceledException = IOException("canceled due to $t")
                        canceledException.addSuppressed(t)
                        responseCallback.onFailure(this@RealCall, canceledException)
                    }
                    throw t
                } finally {
                    client.dispatcher.finished(this) //从runningCalls中移除，并且开始轮询下一个
                }
            }
        }
    }

    internal class CallReference(
            referent: RealCall,
            /**
             * 在call被execute或者是enqueue时来跟踪调用栈。 主要用来监控 connection leak。
             */
            val callStackTrace: Any?
    ) : WeakReference<RealCall>(referent) //弱引用
}
