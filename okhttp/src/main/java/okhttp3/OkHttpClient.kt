/*
 * Copyright (C) 2012 Square, Inc.
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
package okhttp3

import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.security.GeneralSecurityException
import java.time.Duration
import java.util.Collections
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MILLISECONDS
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.Protocol.HTTP_1_1
import okhttp3.Protocol.HTTP_2
import okhttp3.internal.asFactory
import okhttp3.internal.checkDuration
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.connection.RealCall
import okhttp3.internal.connection.RouteDatabase
import okhttp3.internal.immutableListOf
import okhttp3.internal.platform.Platform
import okhttp3.internal.proxy.NullProxySelector
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.tls.OkHostnameVerifier
import okhttp3.internal.toImmutableList
import okhttp3.internal.ws.RealWebSocket
import okio.Sink
import okio.Source
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

/**
 * [Call]的工厂类，用来发送HTTP请求和读取响应
 *
 * OkHttpClient这个类，应该是一个全局共享的类。
 * 一般来说，应该创建单例的`OkHttpClient`，并且针对所有的HTTP call都共用这个client，
 * 这样在性能上会有更好的表现——这是因为每个client都维护了自己的连接池和线程池，
 * 复用连接池和线程可以减少创建它们导致的延迟，并且节约内存。
 *
 * Use `new OkHttpClient()` to create a shared instance with the default settings:
 *
 * ```
 * // The singleton HTTP client.
 * public final OkHttpClient client = new OkHttpClient();
 * ```
 *
 * Or use `new OkHttpClient.Builder()` to create a shared instance with custom settings:
 *
 * ```
 * // The singleton HTTP client.
 * public final OkHttpClient client = new OkHttpClient.Builder()
 *     .addInterceptor(new HttpLoggingInterceptor())
 *     .cache(new Cache(cacheDir, cacheSize))
 *     .build();
 * ```
 *
 * ## 可以通过`newBuilder()`方法来配置参数，这样也是对client的复用。同样会共享连接池和线程池以及原有的配置。
 *
 * You can customize a shared OkHttpClient instance with [newBuilder]. This builds a client that
 * shares the same connection pool, thread pools, and configuration. Use the builder methods to
 * configure the derived client for a specific purpose.
 *
 * This example shows a call with a short 500 millisecond timeout:
 *
 * ```
 * OkHttpClient eagerClient = client.newBuilder()
 *     .readTimeout(500, TimeUnit.MILLISECONDS)
 *     .build();
 * Response response = eagerClient.newCall(request).execute();
 * ```
 *
 * ## 一般来说不用自己主动去释放线程池和连接池的资源的。在它们变成idle状态的时候会被主动释放的。
 * 当然也可以自己直接手动release。通过调用[ExecutorService.shutdown]来释放dispatcher的执行池子，这会导致后续的任务被拒绝。
 * Shutdown the dispatcher's executor service with [shutdown()][ExecutorService.shutdown]. This will
 * also cause future calls to the client to be rejected.
 *
 * ```
 * client.dispatcher().executorService().shutdown();
 * ```
 *
 * Clear the connection pool with [evictAll()][ConnectionPool.evictAll]. Note that the connection
 * pool's daemon thread may not exit immediately.
 *
 * ```
 * client.connectionPool().evictAll();
 * ```
 *
 * If your client has a cache, call [close()][Cache.close]. Note that it is an error to create calls
 * against a cache that is closed, and doing so will cause the call to crash.
 *
 * ```
 * client.cache().close();
 * ```
 *
 * Okhttp针对 HTTP/2 会使用守护线程-因为为了实现HTTP/2连接复用的特性。
 * OkHttp also uses daemon threads for HTTP/2 connections. These will exit automatically if they
 * remain idle.
 */
open class OkHttpClient internal constructor(
        builder: Builder
) : Cloneable, Call.Factory, WebSocket.Factory {

    /**
     * 这个注解的意思是Java调用的时候，生成的字段名是dispatcher
     */
    @get:JvmName("dispatcher")
    val dispatcher: Dispatcher = builder.dispatcher

    //连接池，这是很重要的部分
    @get:JvmName("connectionPool")
    val connectionPool: ConnectionPool = builder.connectionPool

    /**
     * Returns an immutable list of interceptors that observe the full span of each call: from before
     * the connection is established (if any) until after the response source is selected (either the
     * origin server, cache, or both).
     *
     * interceptor在call的整个生命周期内都处于激活状态，它会监听Call的整个过程，从连接建立之前到响应命中。它针对的是[Call]
     */
    @get:JvmName("interceptors")
    val interceptors: List<Interceptor> =
            builder.interceptors.toImmutableList()

    /**
     * Returns an immutable list of interceptors that observe a single network request and response.
     * These interceptors must call [Interceptor.Chain.proceed] exactly once: it is an error for
     * a network interceptor to short-circuit or repeat a network request.
     *
     * networkInterceptor针对的是单个网络请求和响应的过程。这些interceptor只能调用一次[Interceptor.Chain.proceed]方法，也就是
     * 说这个拦截器是一次性的，不能重复使用的。
     */
    @get:JvmName("networkInterceptors")
    val networkInterceptors: List<Interceptor> = builder.networkInterceptors.toImmutableList()

    @get:JvmName("eventListenerFactory")
    val eventListenerFactory: EventListener.Factory = builder.eventListenerFactory

    @get:JvmName("retryOnConnectionFailure")
    val retryOnConnectionFailure: Boolean = builder.retryOnConnectionFailure

    @get:JvmName("authenticator")
    val authenticator: Authenticator = builder.authenticator

    @get:JvmName("followRedirects")
    val followRedirects: Boolean = builder.followRedirects

    @get:JvmName("followSslRedirects")
    val followSslRedirects: Boolean = builder.followSslRedirects

    @get:JvmName("cookieJar")
    val cookieJar: CookieJar = builder.cookieJar

    @get:JvmName("cache")
    val cache: Cache? = builder.cache

    @get:JvmName("dns")
    val dns: Dns = builder.dns

    @get:JvmName("proxy")
    val proxy: Proxy? = builder.proxy

    @get:JvmName("proxySelector")
    val proxySelector: ProxySelector =
            when {
                // Defer calls to ProxySelector.getDefault() because it can throw a SecurityException.
                builder.proxy != null -> NullProxySelector
                else -> builder.proxySelector ?: ProxySelector.getDefault() ?: NullProxySelector
            }

    @get:JvmName("proxyAuthenticator")
    val proxyAuthenticator: Authenticator =
            builder.proxyAuthenticator

    @get:JvmName("socketFactory")
    val socketFactory: SocketFactory = builder.socketFactory

    private val sslSocketFactoryOrNull: SSLSocketFactory?

    @get:JvmName("sslSocketFactory")
    val sslSocketFactory: SSLSocketFactory
        get() = sslSocketFactoryOrNull ?: throw IllegalStateException("CLEARTEXT-only client")

    @get:JvmName("x509TrustManager")
    val x509TrustManager: X509TrustManager?

    @get:JvmName("connectionSpecs")
    val connectionSpecs: List<ConnectionSpec> =
            builder.connectionSpecs

    @get:JvmName("protocols") //支持的协议版本
    val protocols: List<Protocol> = builder.protocols

    @get:JvmName("hostnameVerifier")
    val hostnameVerifier: HostnameVerifier = builder.hostnameVerifier

    @get:JvmName("certificatePinner") //本地自签名证书
    val certificatePinner: CertificatePinner

    @get:JvmName("certificateChainCleaner")
    val certificateChainCleaner: CertificateChainCleaner?

    /**
     * 默认情况下对于一个完整的call是没有超时时间的概念的，但是连接、读、写是都有的
     */
    @get:JvmName("callTimeoutMillis")
    val callTimeoutMillis: Int = builder.callTimeout

    /** 连接超时时间，默认10s. */
    @get:JvmName("connectTimeoutMillis")
    val connectTimeoutMillis: Int = builder.connectTimeout

    /** 读超时时间，默认10s */
    @get:JvmName("readTimeoutMillis")
    val readTimeoutMillis: Int = builder.readTimeout

    /** 写超时时间，默认10s */
    @get:JvmName("writeTimeoutMillis")
    val writeTimeoutMillis: Int = builder.writeTimeout

    /** WebSocket和HTTP/2使用的ping间隔，因为它们需要保持长连接，需要不停发送心跳. */
    @get:JvmName("pingIntervalMillis")
    val pingIntervalMillis: Int = builder.pingInterval

    val routeDatabase: RouteDatabase = builder.routeDatabase ?: RouteDatabase()

    constructor() : this(Builder())

    init {
        if (builder.sslSocketFactoryOrNull != null || connectionSpecs.none { it.isTls }) {
            this.sslSocketFactoryOrNull = builder.sslSocketFactoryOrNull
            this.certificateChainCleaner = builder.certificateChainCleaner
            this.x509TrustManager = builder.x509TrustManagerOrNull
        } else {
            this.x509TrustManager = Platform.get().platformTrustManager()
            Platform.get().configureTrustManager(x509TrustManager)
            this.sslSocketFactoryOrNull = newSslSocketFactory(x509TrustManager!!)
            this.certificateChainCleaner = CertificateChainCleaner.get(x509TrustManager!!)
        }

        if (sslSocketFactoryOrNull != null) {
            Platform.get().configureSslSocketFactory(sslSocketFactoryOrNull)
        }

        this.certificatePinner = builder.certificatePinner
                .withCertificateChainCleaner(certificateChainCleaner)

        check(null !in (interceptors as List<Interceptor?>)) {
            "Null interceptor: $interceptors"
        }
        check(null !in (networkInterceptors as List<Interceptor?>)) {
            "Null network interceptor: $networkInterceptors"
        }
    }

    /** 根据request创建一个新的Call。
     *  request和Call有什么区别？
     *  Call是对整个请求流程的封装。
     */
    override fun newCall(request: Request): Call = RealCall(this, request, forWebSocket = false)

    /** Uses [request] to connect a new web socket. */
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        val webSocket = RealWebSocket(
                TaskRunner.INSTANCE,
                request,
                listener,
                Random(),
                pingIntervalMillis.toLong()
        )
        webSocket.connect(this)
        return webSocket
    }

    /**
     * 建造者模式
     */
    open fun newBuilder(): Builder = Builder(this)

    /**
     * 这些是在kotlin重构过程中，因为kotlin的属性而不需要了的函数，可以直接调用字段。但是为了兼容性，仍然保留下来了，但是不再推荐使用。
     */
//    @JvmName("-deprecated_dispatcher")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "dispatcher"),
//        level = DeprecationLevel.ERROR)
//    fun dispatcher(): Dispatcher = dispatcher
//
//    @JvmName("-deprecated_connectionPool")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "connectionPool"),
//        level = DeprecationLevel.ERROR)
//    fun connectionPool(): ConnectionPool = connectionPool
//
//    @JvmName("-deprecated_interceptors")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "interceptors"),
//        level = DeprecationLevel.ERROR)
//    fun interceptors(): List<Interceptor> = interceptors
//
//    @JvmName("-deprecated_networkInterceptors")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "networkInterceptors"),
//        level = DeprecationLevel.ERROR)
//    fun networkInterceptors(): List<Interceptor> = networkInterceptors
//
//    @JvmName("-deprecated_eventListenerFactory")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "eventListenerFactory"),
//        level = DeprecationLevel.ERROR)
//    fun eventListenerFactory(): EventListener.Factory = eventListenerFactory
//
//    @JvmName("-deprecated_retryOnConnectionFailure")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "retryOnConnectionFailure"),
//        level = DeprecationLevel.ERROR)
//    fun retryOnConnectionFailure(): Boolean = retryOnConnectionFailure
//
//    @JvmName("-deprecated_authenticator")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "authenticator"),
//        level = DeprecationLevel.ERROR)
//    fun authenticator(): Authenticator = authenticator
//
//    @JvmName("-deprecated_followRedirects")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "followRedirects"),
//        level = DeprecationLevel.ERROR)
//    fun followRedirects(): Boolean = followRedirects
//
//    @JvmName("-deprecated_followSslRedirects")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "followSslRedirects"),
//        level = DeprecationLevel.ERROR)
//    fun followSslRedirects(): Boolean = followSslRedirects
//
//    @JvmName("-deprecated_cookieJar")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "cookieJar"),
//        level = DeprecationLevel.ERROR)
//    fun cookieJar(): CookieJar = cookieJar
//
//    @JvmName("-deprecated_cache")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "cache"),
//        level = DeprecationLevel.ERROR)
//    fun cache(): Cache? = cache
//
//    @JvmName("-deprecated_dns")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "dns"),
//        level = DeprecationLevel.ERROR)
//    fun dns(): Dns = dns
//
//    @JvmName("-deprecated_proxy")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "proxy"),
//        level = DeprecationLevel.ERROR)
//    fun proxy(): Proxy? = proxy
//
//    @JvmName("-deprecated_proxySelector")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "proxySelector"),
//        level = DeprecationLevel.ERROR)
//    fun proxySelector(): ProxySelector = proxySelector
//
//    @JvmName("-deprecated_proxyAuthenticator")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "proxyAuthenticator"),
//        level = DeprecationLevel.ERROR)
//    fun proxyAuthenticator(): Authenticator = proxyAuthenticator
//
//    @JvmName("-deprecated_socketFactory")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "socketFactory"),
//        level = DeprecationLevel.ERROR)
//    fun socketFactory(): SocketFactory = socketFactory
//
//    @JvmName("-deprecated_sslSocketFactory")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "sslSocketFactory"),
//        level = DeprecationLevel.ERROR)
//    fun sslSocketFactory(): SSLSocketFactory = sslSocketFactory
//
//    @JvmName("-deprecated_connectionSpecs")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "connectionSpecs"),
//        level = DeprecationLevel.ERROR)
//    fun connectionSpecs(): List<ConnectionSpec> = connectionSpecs
//
//    @JvmName("-deprecated_protocols")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "protocols"),
//        level = DeprecationLevel.ERROR)
//    fun protocols(): List<Protocol> = protocols
//
//    @JvmName("-deprecated_hostnameVerifier")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "hostnameVerifier"),
//        level = DeprecationLevel.ERROR)
//    fun hostnameVerifier(): HostnameVerifier = hostnameVerifier
//
//    @JvmName("-deprecated_certificatePinner")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "certificatePinner"),
//        level = DeprecationLevel.ERROR)
//    fun certificatePinner(): CertificatePinner = certificatePinner
//
//    @JvmName("-deprecated_callTimeoutMillis")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "callTimeoutMillis"),
//        level = DeprecationLevel.ERROR)
//    fun callTimeoutMillis(): Int = callTimeoutMillis
//
//    @JvmName("-deprecated_connectTimeoutMillis")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "connectTimeoutMillis"),
//        level = DeprecationLevel.ERROR)
//    fun connectTimeoutMillis(): Int = connectTimeoutMillis
//
//    @JvmName("-deprecated_readTimeoutMillis")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "readTimeoutMillis"),
//        level = DeprecationLevel.ERROR)
//    fun readTimeoutMillis(): Int = readTimeoutMillis
//
//    @JvmName("-deprecated_writeTimeoutMillis")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "writeTimeoutMillis"),
//        level = DeprecationLevel.ERROR)
//    fun writeTimeoutMillis(): Int = writeTimeoutMillis
//
//    @JvmName("-deprecated_pingIntervalMillis")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "pingIntervalMillis"),
//        level = DeprecationLevel.ERROR)
//    fun pingIntervalMillis(): Int = pingIntervalMillis

    class Builder constructor() {
        internal var dispatcher: Dispatcher = Dispatcher()
        internal var connectionPool: ConnectionPool = ConnectionPool()
        internal val interceptors: MutableList<Interceptor> = mutableListOf()
        internal val networkInterceptors: MutableList<Interceptor> = mutableListOf()
        internal var eventListenerFactory: EventListener.Factory = EventListener.NONE.asFactory()
        internal var retryOnConnectionFailure = true
        internal var authenticator: Authenticator = Authenticator.NONE
        internal var followRedirects = true
        internal var followSslRedirects = true
        internal var cookieJar: CookieJar = CookieJar.NO_COOKIES
        internal var cache: Cache? = null
        internal var dns: Dns = Dns.SYSTEM
        internal var proxy: Proxy? = null
        internal var proxySelector: ProxySelector? = null
        internal var proxyAuthenticator: Authenticator = Authenticator.NONE
        internal var socketFactory: SocketFactory = SocketFactory.getDefault()
        internal var sslSocketFactoryOrNull: SSLSocketFactory? = null
        internal var x509TrustManagerOrNull: X509TrustManager? = null
        internal var connectionSpecs: List<ConnectionSpec> = DEFAULT_CONNECTION_SPECS
        internal var protocols: List<Protocol> = DEFAULT_PROTOCOLS
        internal var hostnameVerifier: HostnameVerifier = OkHostnameVerifier
        internal var certificatePinner: CertificatePinner = CertificatePinner.DEFAULT
        internal var certificateChainCleaner: CertificateChainCleaner? = null
        internal var callTimeout = 0
        internal var connectTimeout = 10_000
        internal var readTimeout = 10_000
        internal var writeTimeout = 10_000
        internal var pingInterval = 0
        internal var routeDatabase: RouteDatabase? = null

        internal constructor(okHttpClient: OkHttpClient) : this() {
            this.dispatcher = okHttpClient.dispatcher
            this.connectionPool = okHttpClient.connectionPool
            this.interceptors += okHttpClient.interceptors
            this.networkInterceptors += okHttpClient.networkInterceptors
            this.eventListenerFactory = okHttpClient.eventListenerFactory
            this.retryOnConnectionFailure = okHttpClient.retryOnConnectionFailure
            this.authenticator = okHttpClient.authenticator
            this.followRedirects = okHttpClient.followRedirects
            this.followSslRedirects = okHttpClient.followSslRedirects
            this.cookieJar = okHttpClient.cookieJar
            this.cache = okHttpClient.cache
            this.dns = okHttpClient.dns
            this.proxy = okHttpClient.proxy
            this.proxySelector = okHttpClient.proxySelector
            this.proxyAuthenticator = okHttpClient.proxyAuthenticator
            this.socketFactory = okHttpClient.socketFactory
            this.sslSocketFactoryOrNull = okHttpClient.sslSocketFactoryOrNull
            this.x509TrustManagerOrNull = okHttpClient.x509TrustManager
            this.connectionSpecs = okHttpClient.connectionSpecs
            this.protocols = okHttpClient.protocols
            this.hostnameVerifier = okHttpClient.hostnameVerifier
            this.certificatePinner = okHttpClient.certificatePinner
            this.certificateChainCleaner = okHttpClient.certificateChainCleaner
            this.callTimeout = okHttpClient.callTimeoutMillis
            this.connectTimeout = okHttpClient.connectTimeoutMillis
            this.readTimeout = okHttpClient.readTimeoutMillis
            this.writeTimeout = okHttpClient.writeTimeoutMillis
            this.pingInterval = okHttpClient.pingIntervalMillis
            this.routeDatabase = okHttpClient.routeDatabase
        }

        /**
         * 外部可以设置Dispatcher来指定内部的异步请求的执行策略。
         */
        fun dispatcher(dispatcher: Dispatcher) = apply {
            this.dispatcher = dispatcher
        }

        /**
         * 连接池的作用在于复用HTTP和HTTPS的连接，建立连接是很消耗资源和时间的。
         * 如果不指定的话，会默认使用[ConnectionPool]
         */
        fun connectionPool(connectionPool: ConnectionPool) = apply {
            this.connectionPool = connectionPool
        }

        /**
         * 返回一个不可变的list：包含了可以监听到每个Call的整个生命周期的interceptor：
         * 从连接建立之前，知道response被选中（这里选中的意思是response可能来自server，也可能来自cache，或者都有）。
         */
        fun interceptors(): MutableList<Interceptor> = interceptors

        //kotlin的特殊操作，通过+=操作来代替add
        fun addInterceptor(interceptor: Interceptor) = apply {
            interceptors += interceptor
        }

        @JvmName("-addInterceptor") // "-"前缀可以禁止重载
        inline fun addInterceptor(crossinline block: (chain: Interceptor.Chain) -> Response) =
                addInterceptor(Interceptor { chain -> block(chain) })

        /**
         * Returns a modifiable list of interceptors that observe a single network request and response.
         * These interceptors must call [Interceptor.Chain.proceed] exactly once: it is an error for a
         * network interceptor to short-circuit or repeat a network request.
         */
        fun networkInterceptors(): MutableList<Interceptor> = networkInterceptors

        fun addNetworkInterceptor(interceptor: Interceptor) = apply {
            networkInterceptors += interceptor
        }

        @JvmName("-addNetworkInterceptor") // Prefix with '-' to prevent ambiguous overloads from Java.
        inline fun addNetworkInterceptor(crossinline block: (chain: Interceptor.Chain) -> Response) =
                addNetworkInterceptor(Interceptor { chain -> block(chain) })

        /**
         * Configure a single client scoped listener that will receive all analytic events for this
         * client.
         * 这个listener针对的是client，一般来说就是整个应用了
         *
         * @see EventListener for semantics and restrictions on listener implementations.
         */
        fun eventListener(eventListener: EventListener) = apply {
            this.eventListenerFactory = eventListener.asFactory()
        }

        /**
         * Configure a factory to provide per-call scoped listeners that will receive analytic events
         * for this client.
         *
         * @see EventListener for semantics and restrictions on listener implementations.
         */
        fun eventListenerFactory(eventListenerFactory: EventListener.Factory) = apply {
            this.eventListenerFactory = eventListenerFactory
        }

        /**
         * 配置重试策略. 默认情况下，当client出现下列问题的时候会自动重试:
         *
         * * **IP地址不可达.** 如果URL指向的host有多个IP地址,对某个IP地址的访问失败不会导致request失败. This can
         *   increase availability of multi-homed services.
         *
         * * **Stale pooled connections.** The [ConnectionPool] reuses sockets
         *   to decrease request latency, but these connections will occasionally time out.
         *
         * * **Unreachable proxy servers.** A [ProxySelector] can be used to
         *   attempt multiple proxy servers in sequence, eventually falling back to a direct
         *   connection.
         *
         * Set this to false to avoid retrying requests when doing so is destructive. In this case the
         * calling application should do its own recovery of connectivity failures.
         */
        fun retryOnConnectionFailure(retryOnConnectionFailure: Boolean) = apply {
            this.retryOnConnectionFailure = retryOnConnectionFailure
        }

        /**
         * Sets the authenticator used to respond to challenges from origin servers. Use
         * [proxyAuthenticator] to set the authenticator for proxy servers.
         *
         * If unset, the [no authentication will be attempted][Authenticator.NONE].
         */
        fun authenticator(authenticator: Authenticator) = apply {
            this.authenticator = authenticator
        }

        /** Configure this client to follow redirects. If unset, redirects will be followed.
         * 重定向
         * */
        fun followRedirects(followRedirects: Boolean) = apply {
            this.followRedirects = followRedirects
        }

        /**
         * Configure this client to follow redirects from HTTPS to HTTP and from HTTP to HTTPS.
         *
         * If unset, protocol redirects will be followed. This is different than the built-in
         * `HttpURLConnection`'s default.
         * HTTP和HTTPS之间的重定向
         */
        fun followSslRedirects(followProtocolRedirects: Boolean) = apply {
            this.followSslRedirects = followProtocolRedirects
        }

        /**
         * Sets the handler that can accept cookies from incoming HTTP responses and provides cookies to
         * outgoing HTTP requests.
         *
         * If unset, [no cookies][CookieJar.NO_COOKIES] will be accepted nor provided.
         */
        fun cookieJar(cookieJar: CookieJar) = apply {
            this.cookieJar = cookieJar
        }

        /** Sets the response cache to be used to read and write cached responses. */
        fun cache(cache: Cache?) = apply {
            this.cache = cache
        }

        /**
         * Sets the DNS service used to lookup IP addresses for hostnames.
         *
         * If unset, the [system-wide default][Dns.SYSTEM] DNS will be used.
         */
        fun dns(dns: Dns) = apply {
            if (dns != this.dns) {
                this.routeDatabase = null
            }
            this.dns = dns
        }

        /**
         * Sets the HTTP proxy that will be used by connections created by this client. This takes
         * precedence over [proxySelector], which is only honored when this proxy is null (which it is
         * by default). To disable proxy use completely, call `proxy(Proxy.NO_PROXY)`.
         */
        fun proxy(proxy: Proxy?) = apply {
            if (proxy != this.proxy) {
                this.routeDatabase = null
            }
            this.proxy = proxy
        }

        /**
         * Sets the proxy selection policy to be used if no [proxy][proxy] is specified explicitly. The
         * proxy selector may return multiple proxies; in that case they will be tried in sequence until
         * a successful connection is established.
         *
         * If unset, the [system-wide default][ProxySelector.getDefault] proxy selector will be used.
         */
        fun proxySelector(proxySelector: ProxySelector) = apply {
            if (proxySelector != this.proxySelector) {
                this.routeDatabase = null
            }

            this.proxySelector = proxySelector
        }

        /**
         * Sets the authenticator used to respond to challenges from proxy servers. Use [authenticator]
         * to set the authenticator for origin servers.
         *
         * If unset, the [no authentication will be attempted][Authenticator.NONE].
         */
        fun proxyAuthenticator(proxyAuthenticator: Authenticator) = apply {
            if (proxyAuthenticator != this.proxyAuthenticator) {
                this.routeDatabase = null
            }

            this.proxyAuthenticator = proxyAuthenticator
        }

        /**
         * Sets the socket factory used to create connections. OkHttp only uses the parameterless
         * [SocketFactory.createSocket] method to create unconnected sockets. Overriding this method,
         * e. g., allows the socket to be bound to a specific local address.
         *
         * If unset, the [system-wide default][SocketFactory.getDefault] socket factory will be used.
         */
        fun socketFactory(socketFactory: SocketFactory) = apply {
            require(socketFactory !is SSLSocketFactory) { "socketFactory instanceof SSLSocketFactory" }

            if (socketFactory != this.socketFactory) {
                this.routeDatabase = null
            }

            this.socketFactory = socketFactory
        }

        /**
         * Sets the socket factory used to secure HTTPS connections. If unset, the system default will
         * be used.
         *
         * @deprecated [SSLSocketFactory] does not expose its [X509TrustManager], which is a field that
         *     OkHttp needs to build a clean certificate chain. This method instead must use reflection
         *     to extract the trust manager. Applications should prefer to call
         *     `sslSocketFactory(SSLSocketFactory, X509TrustManager)`, which avoids such reflection.
         */
        @Deprecated(
                message = "Use the sslSocketFactory overload that accepts a X509TrustManager.",
                level = DeprecationLevel.ERROR
        )
        fun sslSocketFactory(sslSocketFactory: SSLSocketFactory) = apply {
            if (sslSocketFactory != this.sslSocketFactoryOrNull) {
                this.routeDatabase = null
            }

            this.sslSocketFactoryOrNull = sslSocketFactory
            this.certificateChainCleaner = Platform.get().buildCertificateChainCleaner(sslSocketFactory)
        }

        /**
         * Sets the socket factory and trust manager used to secure HTTPS connections. If unset, the
         * system defaults will be used.
         *
         * Most applications should not call this method, and instead use the system defaults. Those
         * classes include special optimizations that can be lost if the implementations are decorated.
         *
         * If necessary, you can create and configure the defaults yourself with the following code:
         *
         * ```
         * TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
         * TrustManagerFactory.getDefaultAlgorithm());
         * trustManagerFactory.init((KeyStore) null);
         * TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
         * if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
         *     throw new IllegalStateException("Unexpected default trust managers:"
         *         + Arrays.toString(trustManagers));
         * }
         * X509TrustManager trustManager = (X509TrustManager) trustManagers[0];
         *
         * SSLContext sslContext = SSLContext.getInstance("TLS");
         * sslContext.init(null, new TrustManager[] { trustManager }, null);
         * SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
         *
         * OkHttpClient client = new OkHttpClient.Builder()
         *     .sslSocketFactory(sslSocketFactory, trustManager)
         *     .build();
         * ```
         *
         * ## TrustManagers on Android are Weird!
         *
         * Trust managers targeting Android must also define a method that has this signature:
         *
         * ```
         *    @SuppressWarnings("unused")
         *    public List<X509Certificate> checkServerTrusted(
         *        X509Certificate[] chain, String authType, String host) throws CertificateException {
         *    }
         * ```
         *
         * This method works like [X509TrustManager.checkServerTrusted] but it receives the hostname of
         * the server as an extra parameter. Regardless of what checks this method performs, OkHttp will
         * always check that the server's certificates match its hostname using the [HostnameVerifier].
         * See [android.net.http.X509TrustManagerExtensions] for more information.
         */
        fun sslSocketFactory(
                sslSocketFactory: SSLSocketFactory,
                trustManager: X509TrustManager
        ) = apply {
            if (sslSocketFactory != this.sslSocketFactoryOrNull || trustManager != this.x509TrustManagerOrNull) {
                this.routeDatabase = null
            }

            this.sslSocketFactoryOrNull = sslSocketFactory
            this.certificateChainCleaner = CertificateChainCleaner.get(trustManager)
            this.x509TrustManagerOrNull = trustManager
        }

        fun connectionSpecs(connectionSpecs: List<ConnectionSpec>) = apply {
            if (connectionSpecs != this.connectionSpecs) {
                this.routeDatabase = null
            }

            this.connectionSpecs = connectionSpecs.toImmutableList()
        }

        /**
         * Configure the protocols used by this client to communicate with remote servers. By default
         * this client will prefer the most efficient transport available, falling back to more
         * ubiquitous protocols. Applications should only call this method to avoid specific
         * compatibility problems, such as web servers that behave incorrectly when HTTP/2 is enabled.
         *
         * The following protocols are currently supported:
         *
         * * [http/1.1][rfc_2616]
         * * [h2][rfc_7540]
         * * [h2 with prior knowledge(cleartext only)][rfc_7540_34]
         *
         * **This is an evolving set.** Future releases include support for transitional
         * protocols. The http/1.1 transport will never be dropped.
         *
         * If multiple protocols are specified, [ALPN][alpn] will be used to negotiate a transport.
         * Protocol negotiation is only attempted for HTTPS URLs.
         *
         * [Protocol.HTTP_1_0] is not supported in this set. Requests are initiated with `HTTP/1.1`. If
         * the server responds with `HTTP/1.0`, that will be exposed by [Response.protocol].
         *
         * [alpn]: http://tools.ietf.org/html/draft-ietf-tls-applayerprotoneg
         * [rfc_2616]: http://www.w3.org/Protocols/rfc2616/rfc2616.html
         * [rfc_7540]: https://tools.ietf.org/html/rfc7540
         * [rfc_7540_34]: https://tools.ietf.org/html/rfc7540#section-3.4
         *
         * @param protocols the protocols to use, in order of preference. If the list contains
         *     [Protocol.H2_PRIOR_KNOWLEDGE] then that must be the only protocol and HTTPS URLs will not
         *     be supported. Otherwise the list must contain [Protocol.HTTP_1_1]. The list must
         *     not contain null or [Protocol.HTTP_1_0].
         */
        fun protocols(protocols: List<Protocol>) = apply {
            // Create a private copy of the list.
            val protocolsCopy = protocols.toMutableList()

            // Validate that the list has everything we require and nothing we forbid.
            require(Protocol.H2_PRIOR_KNOWLEDGE in protocolsCopy || HTTP_1_1 in protocolsCopy) {
                "protocols must contain h2_prior_knowledge or http/1.1: $protocolsCopy"
            }
            require(Protocol.H2_PRIOR_KNOWLEDGE !in protocolsCopy || protocolsCopy.size <= 1) {
                "protocols containing h2_prior_knowledge cannot use other protocols: $protocolsCopy"
            }
            require(Protocol.HTTP_1_0 !in protocolsCopy) {
                "protocols must not contain http/1.0: $protocolsCopy"
            }
            require(null !in (protocolsCopy as List<Protocol?>)) {
                "protocols must not contain null"
            }

            // Remove protocols that we no longer support.
            @Suppress("DEPRECATION")
            protocolsCopy.remove(Protocol.SPDY_3)

            if (protocolsCopy != this.protocols) {
                this.routeDatabase = null
            }

            // Assign as an unmodifiable list. This is effectively immutable.
            this.protocols = Collections.unmodifiableList(protocolsCopy)
        }

        /**
         * Sets the verifier used to confirm that response certificates apply to requested hostnames for
         * HTTPS connections.
         *
         * If unset, a default hostname verifier will be used.
         */
        fun hostnameVerifier(hostnameVerifier: HostnameVerifier) = apply {
            if (hostnameVerifier != this.hostnameVerifier) {
                this.routeDatabase = null
            }

            this.hostnameVerifier = hostnameVerifier
        }

        /**
         * Sets the certificate pinner that constrains which certificates are trusted. By default HTTPS
         * connections rely on only the [SSL socket factory][sslSocketFactory] to establish trust.
         * Pinning certificates avoids the need to trust certificate authorities.
         */
        fun certificatePinner(certificatePinner: CertificatePinner) = apply {
            if (certificatePinner != this.certificatePinner) {
                this.routeDatabase = null
            }

            this.certificatePinner = certificatePinner
        }

        /**
         * Sets the default timeout for complete calls. A value of 0 means no timeout, otherwise values
         * must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
         *
         * The call timeout spans the entire call: resolving DNS, connecting, writing the request body,
         * server processing, and reading the response body. If the call requires redirects or retries
         * all must complete within one timeout period.
         *
         * The default value is 0 which imposes no timeout.
         */
        fun callTimeout(timeout: Long, unit: TimeUnit) = apply {
            callTimeout = checkDuration("timeout", timeout, unit)
        }

        /**
         * Sets the default timeout for complete calls. A value of 0 means no timeout, otherwise values
         * must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
         *
         * The call timeout spans the entire call: resolving DNS, connecting, writing the request body,
         * server processing, and reading the response body. If the call requires redirects or retries
         * all must complete within one timeout period.
         *
         * The default value is 0 which imposes no timeout.
         */
        @IgnoreJRERequirement
        fun callTimeout(duration: Duration) = apply {
            callTimeout(duration.toMillis(), MILLISECONDS)
        }

        /**
         * Sets the default connect timeout for new connections. A value of 0 means no timeout,
         * otherwise values must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
         *
         * The connect timeout is applied when connecting a TCP socket to the target host. The default
         * value is 10 seconds.
         */
        fun connectTimeout(timeout: Long, unit: TimeUnit) = apply {
            connectTimeout = checkDuration("timeout", timeout, unit)
        }

        /**
         * Sets the default connect timeout for new connections. A value of 0 means no timeout,
         * otherwise values must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
         *
         * The connect timeout is applied when connecting a TCP socket to the target host. The default
         * value is 10 seconds.
         */
        @IgnoreJRERequirement
        fun connectTimeout(duration: Duration) = apply {
            connectTimeout(duration.toMillis(), MILLISECONDS)
        }

        /**
         * Sets the default read timeout for new connections. A value of 0 means no timeout, otherwise
         * values must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
         *
         * The read timeout is applied to both the TCP socket and for individual read IO operations
         * including on [Source] of the [Response]. The default value is 10 seconds.
         *
         * @see Socket.setSoTimeout
         * @see Source.timeout
         */
        fun readTimeout(timeout: Long, unit: TimeUnit) = apply {
            readTimeout = checkDuration("timeout", timeout, unit)
        }

        /**
         * Sets the default read timeout for new connections. A value of 0 means no timeout, otherwise
         * values must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
         *
         * The read timeout is applied to both the TCP socket and for individual read IO operations
         * including on [Source] of the [Response]. The default value is 10 seconds.
         *
         * @see Socket.setSoTimeout
         * @see Source.timeout
         */
        @IgnoreJRERequirement
        fun readTimeout(duration: Duration) = apply {
            readTimeout(duration.toMillis(), MILLISECONDS)
        }

        /**
         * Sets the default write timeout for new connections. A value of 0 means no timeout, otherwise
         * values must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
         *
         * The write timeout is applied for individual write IO operations. The default value is 10
         * seconds.
         *
         * @see Sink.timeout
         */
        fun writeTimeout(timeout: Long, unit: TimeUnit) = apply {
            writeTimeout = checkDuration("timeout", timeout, unit)
        }

        /**
         * Sets the default write timeout for new connections. A value of 0 means no timeout, otherwise
         * values must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
         *
         * The write timeout is applied for individual write IO operations. The default value is 10
         * seconds.
         *
         * @see Sink.timeout
         */
        @IgnoreJRERequirement
        fun writeTimeout(duration: Duration) = apply {
            writeTimeout(duration.toMillis(), MILLISECONDS)
        }

        /**
         * Sets the interval between HTTP/2 and web socket pings initiated by this client. Use this to
         * automatically send ping frames until either the connection fails or it is closed. This keeps
         * the connection alive and may detect connectivity failures.
         *
         * If the server does not respond to each ping with a pong within `interval`, this client will
         * assume that connectivity has been lost. When this happens on a web socket the connection is
         * canceled and its listener is [notified][WebSocketListener.onFailure]. When it happens on an
         * HTTP/2 connection the connection is closed and any calls it is carrying
         * [will fail with an IOException][java.io.IOException].
         *
         * The default value of 0 disables client-initiated pings.
         */
        fun pingInterval(interval: Long, unit: TimeUnit) = apply {
            pingInterval = checkDuration("interval", interval, unit)
        }

        /**
         * Sets the interval between HTTP/2 and web socket pings initiated by this client. Use this to
         * automatically send ping frames until either the connection fails or it is closed. This keeps
         * the connection alive and may detect connectivity failures.
         *
         * If the server does not respond to each ping with a pong within `interval`, this client will
         * assume that connectivity has been lost. When this happens on a web socket the connection is
         * canceled and its listener is [notified][WebSocketListener.onFailure]. When it happens on an
         * HTTP/2 connection the connection is closed and any calls it is carrying
         * [will fail with an IOException][java.io.IOException].
         *
         * The default value of 0 disables client-initiated pings.
         */
        @IgnoreJRERequirement
        fun pingInterval(duration: Duration) = apply {
            pingInterval(duration.toMillis(), MILLISECONDS)
        }

        fun build(): OkHttpClient = OkHttpClient(this)
    }

    companion object {
        internal val DEFAULT_PROTOCOLS = immutableListOf(HTTP_2, HTTP_1_1)

        internal val DEFAULT_CONNECTION_SPECS = immutableListOf(
                ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)

        private fun newSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory {
            try {
                val sslContext = Platform.get().newSSLContext()
                sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
                return sslContext.socketFactory
            } catch (e: GeneralSecurityException) {
                throw AssertionError("No System TLS", e) // The system has no TLS. Just give up.
            }
        }
    }
}
