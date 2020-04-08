/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.http

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection.HTTP_CLIENT_TIMEOUT
import java.net.HttpURLConnection.HTTP_MOVED_PERM
import java.net.HttpURLConnection.HTTP_MOVED_TEMP
import java.net.HttpURLConnection.HTTP_MULT_CHOICE
import java.net.HttpURLConnection.HTTP_PROXY_AUTH
import java.net.HttpURLConnection.HTTP_SEE_OTHER
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import java.net.HttpURLConnection.HTTP_UNAVAILABLE
import java.net.ProtocolException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.canReuseConnectionFor
import okhttp3.internal.closeQuietly
import okhttp3.internal.connection.Exchange
import okhttp3.internal.connection.RealCall
import okhttp3.internal.connection.RouteException
import okhttp3.internal.http.StatusLine.Companion.HTTP_MISDIRECTED_REQUEST
import okhttp3.internal.http.StatusLine.Companion.HTTP_PERM_REDIRECT
import okhttp3.internal.http.StatusLine.Companion.HTTP_TEMP_REDIRECT
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.withSuppressed

/**
 * 重试和重定向拦截器。It may throw an [IOException] if the call was canceled.
 */
class RetryAndFollowUpInterceptor(private val client: OkHttpClient) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val realChain = chain as RealInterceptorChain
        var request = chain.request
        val call = realChain.call
        var followUpCount = 0 //重定向次数
        var priorResponse: Response? = null
        var newExchangeFinder = true
        var recoveredFailures = listOf<IOException>()
        //通过一个循环来重新尝试请求
        while (true) {
            call.enterNetworkInterceptorExchange(request, newExchangeFinder)

            var response: Response
            var closeActiveExchange = true
            try {
                //请求被取消了，抛异常
                if (call.isCanceled()) {
                    throw IOException("Canceled")
                }

                try {
                    response = realChain.proceed(request) //核心在这里，进行网络请求。这里的realChain是链中的下一个节点
                    newExchangeFinder = true //这个字段成功的时候才会被置为true
                } catch (e: RouteException) {
                    //通过[Rout]进行连接的时候失败了，请求根本没发出去
                    //这种情况下其实已经重连尝试了很多次了。
                    if (!recover(e.lastConnectException, call, request, requestSendStarted = false)) {
                        throw e.firstConnectException.withSuppressed(recoveredFailures)
                    } else {
                        //这里会把重试过程中遇到的错误记录下来
                        recoveredFailures += e.firstConnectException
                    }
                    newExchangeFinder = false
                    continue //continue其实就是重试
                } catch (e: IOException) {
                    // 和服务端通信的时候失败了. 请求没有发出去
                    if (!recover(e, call, request, requestSendStarted = e !is ConnectionShutdownException)) {
                        throw e.withSuppressed(recoveredFailures)
                    } else {
                        //这里会把重试过程中遇到的错误记录下来
                        recoveredFailures += e
                    }
                    newExchangeFinder = false
                    continue
                }

                // 只有在第一次或者是重试成功了之后才会走到这里来，否则上面就直接抛出异常了
                // 把上一次的响应附加到这一次上，priorResponse是没有body的
                // Attach the prior response if it exists. Such responses never have a body.
                if (priorResponse != null) {
                    response = response.newBuilder()
                            .priorResponse(priorResponse.newBuilder()
                                    .body(null)
                                    .build())
                            .build()
                }

                val exchange = call.interceptorScopedExchange
                val followUp = followUpRequest(response, exchange)

                if (followUp == null) {
                    if (exchange != null && exchange.isDuplex) {
                        call.timeoutEarlyExit()
                    }
                    closeActiveExchange = false
                    return response
                }

                val followUpBody = followUp.body
                if (followUpBody != null && followUpBody.isOneShot()) {
                    closeActiveExchange = false
                    return response
                }

                response.body?.closeQuietly()
                //能走到这里来，说明已经进行重连了
                if (++followUpCount > MAX_FOLLOW_UPS) { //最大重试20次
                    throw ProtocolException("Too many follow-up requests: $followUpCount")
                }

                request = followUp
                priorResponse = response
            } finally {
                call.exitNetworkInterceptorExchange(closeActiveExchange)
            }
        }
    }

    /**
     * 这个recover就是尝试重连的核心实现.
     * 首先根据传入的这个exception判断是不是可重试的错误类型, 如果是返回true，否则返回false。
     * 带有请求体的request只有在请求体被缓存了的情况下，或者是请求根本没发出时可以被恢复。
     */
    private fun recover(
            e: IOException,
            call: RealCall,
            userRequest: Request,
            requestSendStarted: Boolean
    ): Boolean {
        // 应用层禁止了重试.
        if (!client.retryOnConnectionFailure) return false

        // request已经发出去了，或者是requestIsOneShot，这种情况下不能再次发送请求
        if (requestSendStarted && requestIsOneShot(e, userRequest)) return false

        // Exception是致命的。
        if (!isRecoverable(e, requestSendStarted)) return false

        // 所有的routes都尝试完了，无法继续重试
        if (!call.retryAfterFailure()) return false

        // For failure recovery, use the same route selector with a new connection.
        return true
    }

    /**
     * 当前请求是不是一次性的。
     * 也就是不能重试
     */
    private fun requestIsOneShot(e: IOException, userRequest: Request): Boolean {
        val requestBody = userRequest.body
        return (requestBody != null && requestBody.isOneShot()) ||
                e is FileNotFoundException
    }

    private fun isRecoverable(e: IOException, requestSendStarted: Boolean): Boolean {
        // protocol层面的问题，不可恢复
        if (e is ProtocolException) {
            return false
        }

        // If there was an interruption don't recover, but if there was a timeout connecting to a route
        // we should try the next route (if there is one).
        if (e is InterruptedIOException) {
            return e is SocketTimeoutException && !requestSendStarted
        }

        // Look for known client-side or negotiation errors that are unlikely to be fixed by trying
        // again with a different route.
        if (e is SSLHandshakeException) {
            // If the problem was a CertificateException from the X509TrustManager,
            // do not retry.
            if (e.cause is CertificateException) {
                return false
            }
        }
        if (e is SSLPeerUnverifiedException) {
            // e.g. a certificate pinning error.
            return false
        }
        // An example of one we might want to retry with a different route is a problem connecting to a
        // proxy and would manifest as a standard IOException. Unless it is one we know we should not
        // retry, we return true and try a new route.
        return true
    }

    /**
     *
     * 根据userResponse来判断请求的情况，决定是不是要添加authentication headers、重定向、超时重试等，
     * 也就是请求出了问题，需要重试，然后在这个方法中构建出重试的request。
     * 根据各种具体的错误类型来构造对应的重新发送请求的request。
     * 如果都不需要的话，就会返回null。
     */
    @Throws(IOException::class)
    private fun followUpRequest(userResponse: Response, exchange: Exchange?): Request? {
        val route = exchange?.connection?.route()
        val responseCode = userResponse.code

        val method = userResponse.request.method
        when (responseCode) {
            // 407，没有满足代理服务器需要的身份认证，和401类似
            HTTP_PROXY_AUTH -> {
                val selectedProxy = route!!.proxy
                if (selectedProxy.type() != Proxy.Type.HTTP) {
                    throw ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy")
                }
                return client.proxyAuthenticator.authenticate(route, userResponse)
            }
            // 401，缺乏身份认证
            HTTP_UNAUTHORIZED -> return client.authenticator.authenticate(route, userResponse)
            // 307,308,临时重定向和永久重定向
            HTTP_PERM_REDIRECT, HTTP_TEMP_REDIRECT -> {
                //出了GET和HEAD请求，其他请求禁止通过307和308重定向。
                if (method != "GET" && method != "HEAD") {
                    return null
                }
                //构建重定向请求
                return buildRedirectRequest(userResponse, method)
            }
            // 300,301,302,303,表示资源不在当前位置了，需要重定向
            HTTP_MULT_CHOICE, HTTP_MOVED_PERM, HTTP_MOVED_TEMP, HTTP_SEE_OTHER -> {
                return buildRedirectRequest(userResponse, method)
            }
            // 客户端请求超时
            HTTP_CLIENT_TIMEOUT -> {
                // 408在实际应用中是很少见的。它表示我们应该不修改请求，直接再次发起。
                if (!client.retryOnConnectionFailure) {
                    // The application layer has directed us not to retry the request.
                    return null
                }

                val requestBody = userResponse.request.body
                if (requestBody != null && requestBody.isOneShot()) {
                    return null
                }
                val priorResponse = userResponse.priorResponse
                if (priorResponse != null && priorResponse.code == HTTP_CLIENT_TIMEOUT) {
                    // We attempted to retry and got another timeout. Give up.
                    return null
                }

                if (retryAfter(userResponse, 0) > 0) {
                    return null
                }

                return userResponse.request
            }
            // 503,，服务不可用
            HTTP_UNAVAILABLE -> {
                val priorResponse = userResponse.priorResponse
                if (priorResponse != null && priorResponse.code == HTTP_UNAVAILABLE) {
                    // 连续两次服务不可用就放弃.
                    return null
                }
                //一段时间后再次尝试
                if (retryAfter(userResponse, Integer.MAX_VALUE) == 0) {
                    // specifically received an instruction to retry without delay
                    return userResponse.request
                }

                return null
            }
            // 421，从当前客户端所在的IP地址到服务器的连接数超过了服务器许可的最大范围
            HTTP_MISDIRECTED_REQUEST -> {
                // 由于OkHttp可以针对HTTP/2合并连接，即使他们的主机名不同。[RealConnection.isEligible()]
                // If we attempted this and the server returned HTTP 421, then
                // we can retry on a different connection.
                val requestBody = userResponse.request.body
                if (requestBody != null && requestBody.isOneShot()) {
                    return null
                }

                if (exchange == null || !exchange.isCoalescedConnection) { //如果是合并过的连接
                    return null
                }

                exchange.connection.noCoalescedConnections() //禁止当前连接被除了Route中声明的host之外的复用。
                return userResponse.request
            }

            else -> return null
        }
    }

    /**
     * 构建重定向请求
     */
    private fun buildRedirectRequest(userResponse: Response, method: String): Request? {
        // 先判断OKHttpClient的设置中是否允许重定向
        if (!client.followRedirects) return null

        val location = userResponse.header("Location") ?: return null
        // 不能重定向到不支持的协议
        val url = userResponse.request.url.resolve(location) ?: return null

        // 不能在http和HTTPS之间重定向
        val sameScheme = url.scheme == userResponse.request.url.scheme
        if (!sameScheme && !client.followSslRedirects) return null

        // 大多数重定向是不包含请求体的，//get和head方法没有body
        val requestBuilder = userResponse.request.newBuilder() //这里就构建了request
        if (HttpMethod.permitsRequestBody(method)) {
            val maintainBody = HttpMethod.redirectsWithBody(method)
            if (HttpMethod.redirectsToGet(method)) {
                requestBuilder.method("GET", null)
            } else {
                val requestBody = if (maintainBody) userResponse.request.body else null
                requestBuilder.method(method, requestBody)
            }
            if (!maintainBody) {
                requestBuilder.removeHeader("Transfer-Encoding")
                requestBuilder.removeHeader("Content-Length")
                requestBuilder.removeHeader("Content-Type")
            }
        }

        //当跨越hosts进行重定向时，需要丢掉所有请求认证相关的header，因为是新的host了，旧的没有意义。
        if (!userResponse.request.url.canReuseConnectionFor(url)) {
            requestBuilder.removeHeader("Authorization")
        }

        return requestBuilder.url(url).build()
    }

    private fun retryAfter(userResponse: Response, defaultDelay: Int): Int {
        val header = userResponse.header("Retry-After") ?: return defaultDelay

        // https://tools.ietf.org/html/rfc7231#section-7.1.3
        // currently ignores a HTTP-date, and assumes any non int 0 is a delay
        if (header.matches("\\d+".toRegex())) {
            return Integer.valueOf(header)
        }
        return Integer.MAX_VALUE
    }

    companion object {
        /**
         * 最大的重定向次数是多少合适? Chrome是21次; Firefox,curl, and wget 是 20; Safari 是 16; and HTTP/1.0 recommends 5.
         */
        private const val MAX_FOLLOW_UPS = 20 //最大重定向次数
    }
}
