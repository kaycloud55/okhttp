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
import java.net.ProtocolException
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.EMPTY_RESPONSE
import okio.buffer

/**
 * 责任链中的最后一个interceptor，负责向服务器发送请求。
 */
class CallServerInterceptor(private val forWebSocket: Boolean) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val realChain = chain as RealInterceptorChain
        val exchange = realChain.exchange!!
        val request = realChain.request
        val requestBody = request.body
        val sentRequestMillis = System.currentTimeMillis() //发送请求的时间点

        exchange.writeRequestHeaders(request) //把header写入到exchange中

        var invokeStartEvent = true
        var responseBuilder: Response.Builder? = null
        // 如果是非GET/HEAD方法，也就是除了这两个方法之外的其他方法可以有requestBody，并且requestBody不为空
        if (HttpMethod.permitsRequestBody(request.method) && requestBody != null) {
            // 如果header中有 "Expect: 100-continue"（表示一切正常，客户端应该继续请求），就应该先等服务端返回了"HTTP/1.1 100 Continue"之后再发送requestBody。
            // 这种操作一般是为了让服务端检查header是否合法。
            // 如果没有这个字段的话，就如果返回得到的结果，不对requestBody做任何处理。
            if ("100-continue".equals(request.header("Expect"), ignoreCase = true)) {
                exchange.flushRequest()
                responseBuilder = exchange.readResponseHeaders(expectContinue = true) //这里可以看出来其实真正的请求是通过exchange发送的
                exchange.responseHeadersStart()
                invokeStartEvent = false
            }
            // ==null表示请求体的header中没有“100 expect",是普通的请求
            if (responseBuilder == null) {
                //请求体是否是双工的
                if (requestBody.isDuplex()) {
                    // Prepare a duplex body so that the application can send a request body later.
                    exchange.flushRequest()
                    val bufferedRequestBody = exchange.createRequestBody(request, true).buffer()
                    requestBody.writeTo(bufferedRequestBody)
                } else {
                    // Write the request body if the "Expect: 100-continue" expectation was met.
                    val bufferedRequestBody = exchange.createRequestBody(request, false).buffer()
                    requestBody.writeTo(bufferedRequestBody)
                    bufferedRequestBody.close()
                }
            } else {
                exchange.noRequestBody()
                if (!exchange.connection.isMultiplexed) {
                    // If the "Expect: 100-continue" expectation wasn't met, prevent the HTTP/1 connection
                    // from being reused. Otherwise we're still obligated to transmit the request body to
                    // leave the connection in a consistent state.
                    exchange.noNewExchangesOnConnection()
                }
            }
        } else {
            exchange.noRequestBody()
        }
        //上面完成了请求的构建
        if (requestBody == null || !requestBody.isDuplex()) {
            //直接完成请求
            exchange.finishRequest()
        }
        if (responseBuilder == null) {
            responseBuilder = exchange.readResponseHeaders(expectContinue = false)!! //读取
            if (invokeStartEvent) {
                exchange.responseHeadersStart()
                invokeStartEvent = false
            }
        }
        //这里是普通情况（除了100 continue以外），第一次请求就可以得到对应的数据
        var response = responseBuilder
                .request(request)
                .handshake(exchange.connection.handshake())
                .sentRequestAtMillis(sentRequestMillis)
                .receivedResponseAtMillis(System.currentTimeMillis())
                .build()
        var code = response.code
        // 如果服务端返回了100，就需要继续发起请求
        if (code == 100) {
            responseBuilder = exchange.readResponseHeaders(expectContinue = false)!! //这里会读取响应的header并填入response
            if (invokeStartEvent) {
                exchange.responseHeadersStart()
            }
            response = responseBuilder
                    .request(request) //写入request数据
                    .handshake(exchange.connection.handshake()) //写入TLS握手数据
                    .sentRequestAtMillis(sentRequestMillis) //写入发送请求的时间
                    .receivedResponseAtMillis(System.currentTimeMillis()) //写入接受到请求的时间
                    .build()
            code = response.code
        }

        exchange.responseHeadersEnd(response) //请求完成了，不需要再次发起了

        response = if (forWebSocket && code == 101) {
            // Connection is upgrading, but we need to ensure interceptors see a non-null response body.
            response.newBuilder()
                    .body(EMPTY_RESPONSE)
                    .build()
        } else {
            //构建请求
            response.newBuilder()
                    .body(exchange.openResponseBody(response))
                    .build()
        }
        //客户端或者服务端要求关闭连接
        if ("close".equals(response.request.header("Connection"), ignoreCase = true) ||
                "close".equals(response.header("Connection"), ignoreCase = true)) {
            exchange.noNewExchangesOnConnection() //没有数据交换了，关闭连接
        }
        if ((code == 204 || code == 205) && response.body?.contentLength() ?: -1L > 0L) {
            throw ProtocolException(
                    "HTTP $code had non-zero Content-Length: ${response.body?.contentLength()}")
        }
        return response
    }
}
