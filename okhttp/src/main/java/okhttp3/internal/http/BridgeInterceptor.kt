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
package okhttp3.internal.http

import java.io.IOException
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.toHostHeader
import okio.GzipSource
import okio.buffer
import sun.net.www.protocol.http.HttpURLConnection.userAgent

/**
 * 应用层和网络层的桥接拦截器，主要工作是为请求添加cookie、添加固定的header，
 * 比如Host、Content-Length、Content-Type、User-Agent等，然后保存响应结果的cookie，如果响应使用gzip解压过，则还需要进行解压。
 */
class BridgeInterceptor(private val cookieJar: CookieJar) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val userRequest = chain.request()
        val requestBuilder = userRequest.newBuilder()

        val body = userRequest.body
        //根据请求体，为header添加一次参数
        if (body != null) {
            val contentType = body.contentType()
            if (contentType != null) {
                requestBuilder.header("Content-Type", contentType.toString())
            }

            val contentLength = body.contentLength()
            if (contentLength != -1L) {
                requestBuilder.header("Content-Length", contentLength.toString())
                requestBuilder.removeHeader("Transfer-Encoding")
            } else {
                requestBuilder.header("Transfer-Encoding", "chunked")
                requestBuilder.removeHeader("Content-Length")
            }
        }

        if (userRequest.header("Host") == null) {
            requestBuilder.header("Host", userRequest.url.toHostHeader())
        }

        if (userRequest.header("Connection") == null) {
            requestBuilder.header("Connection", "Keep-Alive") //默认长连接
        }

        // OkHttp添加了 "Accept-Encoding: gzip"头字段的话，OkHttp也要负责解压。
        var transparentGzip = false
        if (userRequest.header("Accept-Encoding") == null && userRequest.header("Range") == null) {
            transparentGzip = true
            requestBuilder.header("Accept-Encoding", "gzip")
        }

        val cookies = cookieJar.loadForRequest(userRequest.url) //根据url找可用的cookie
        if (cookies.isNotEmpty()) {
            requestBuilder.header("Cookie", cookieHeader(cookies))
        }

        if (userRequest.header("User-Agent") == null) {
            requestBuilder.header("User-Agent", userAgent)
        }
        //交给下一级去处理，这里的chain就是下一级
        val networkResponse = chain.proceed(requestBuilder.build())

        cookieJar.receiveHeaders(userRequest.url, networkResponse.headers)

        val responseBuilder = networkResponse.newBuilder()
                .request(userRequest)

        //如果OkHttp自动添加了gzip的话，也要负责解压的工作
        if (transparentGzip &&
                "gzip".equals(networkResponse.header("Content-Encoding"), ignoreCase = true) &&
                networkResponse.promisesBody()) {
            val responseBody = networkResponse.body
            if (responseBody != null) {
                val gzipSource = GzipSource(responseBody.source())
                val strippedHeaders = networkResponse.headers.newBuilder()
                        .removeAll("Content-Encoding")
                        .removeAll("Content-Length")
                        .build()
                responseBuilder.headers(strippedHeaders)
                val contentType = networkResponse.header("Content-Type")
                responseBuilder.body(RealResponseBody(contentType, -1L, gzipSource.buffer()))
            }
        }

        return responseBuilder.build()
    }

    /** Returns a 'Cookie' HTTP request header with all cookies, like `a=b; c=d`. */
    private fun cookieHeader(cookies: List<Cookie>): String = buildString {
        cookies.forEachIndexed { index, cookie ->
            if (index > 0) append("; ")
            append(cookie.name).append('=').append(cookie.value)
        }
    }
}
