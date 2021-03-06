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

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.checkDuration
import okhttp3.internal.connection.Exchange
import okhttp3.internal.connection.RealCall

/**
 * 包含了所有interceptor的责任链，它包含了所有层面的处理：所有的应用拦截器、OKHttp的核心拦截器、网络拦截器、网络调用方拦截器
 * 这个类表示的是链条上的一个节点，通过index来标识它在链条中的位置。
 * A concrete interceptor chain that carries the entire interceptor chain: all application
 * interceptors, the OkHttp core, all network interceptors, and finally the network caller.
 *
 * If the chain is for an application interceptor then [exchange] must be null. Otherwise it is for
 * a network interceptor and [exchange] must be non-null.
 *
 * 对于interceptor和network interceptor，exchange的表现是不一样的
 */
class RealInterceptorChain(
        internal val call: RealCall,
        private val interceptors: List<Interceptor>,
        private val index: Int,
        internal val exchange: Exchange?,
        internal val request: Request,
        internal val connectTimeoutMillis: Int,
        internal val readTimeoutMillis: Int,
        internal val writeTimeoutMillis: Int
) : Interceptor.Chain {

    private var calls: Int = 0

    internal fun copy(
            index: Int = this.index,
            exchange: Exchange? = this.exchange,
            request: Request = this.request,
            connectTimeoutMillis: Int = this.connectTimeoutMillis,
            readTimeoutMillis: Int = this.readTimeoutMillis,
            writeTimeoutMillis: Int = this.writeTimeoutMillis
    ) = RealInterceptorChain(call, interceptors, index, exchange, request, connectTimeoutMillis,
            readTimeoutMillis, writeTimeoutMillis)

    override fun connection(): Connection? = exchange?.connection

    override fun connectTimeoutMillis(): Int = connectTimeoutMillis

    override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
            copy(connectTimeoutMillis = checkDuration("connectTimeout", timeout.toLong(), unit))

    override fun readTimeoutMillis(): Int = readTimeoutMillis

    override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
            copy(readTimeoutMillis = checkDuration("readTimeout", timeout.toLong(), unit))

    override fun writeTimeoutMillis(): Int = writeTimeoutMillis

    override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
            copy(writeTimeoutMillis = checkDuration("writeTimeout", timeout.toLong(), unit))

    override fun call(): Call = call

    override fun request(): Request = request

    @Throws(IOException::class)
    override fun proceed(request: Request): Response {
        check(index < interceptors.size)
        //统计当前拦截器调用proceed方法的次数
        calls++

        //exchange是对请求流的封装，在执行ConnectInterceptor前为空，连接和流已经建立但此时此连接不再支持当前url
        // 说明之前的网络拦截器对url或端口进行了修改，这是不允许的。
        if (exchange != null) {
            check(exchange.connection.supportsUrl(request.url)) {
                "network interceptor ${interceptors[index - 1]} must retain the same host and port"
            }
            // 这里是对拦截器调用proceed方法的限制，在ConnectInterceptor及其之后的拦截器最多只能调用一次proceed方法。
            check(calls == 1) {
                "network interceptor ${interceptors[index - 1]} must call proceed() exactly once"
            }
        }

        // 创建下一层责任链，注意index + 1，注意是chain，而不是interceptor。
        // 其实也就是将一个个的interceptor包装成一个个Chain。
        val next = copy(index = index + 1, request = request)
        // 取出下标为index的拦截器，并调用其intercept方法，将新建的链传入
        val interceptor = interceptors[index]

        @Suppress("USELESS_ELVIS")
        //这里是关键，会设置下一个interceptor，串成一个完整的链条
        val response = interceptor.intercept(next) ?: throw NullPointerException(
                "interceptor $interceptor returned null")

        if (exchange != null) {
            //保证在ConnectInterceptor及其之后的拦截器至少调用一次proceed!!
            //在connectInterceptor之后的其实都是networkInterceptor。
            check(index + 1 >= interceptors.size || next.calls == 1) {
                "network interceptor $interceptor must call proceed() exactly once"
            }
        }

        check(response.body != null) { "interceptor $interceptor returned a response with no body" }

        return response
    }
}
