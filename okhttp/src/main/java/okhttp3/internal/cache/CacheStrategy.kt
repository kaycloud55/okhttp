/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3.internal.cache

import java.net.HttpURLConnection.HTTP_BAD_METHOD
import java.net.HttpURLConnection.HTTP_GONE
import java.net.HttpURLConnection.HTTP_MOVED_PERM
import java.net.HttpURLConnection.HTTP_MOVED_TEMP
import java.net.HttpURLConnection.HTTP_MULT_CHOICE
import java.net.HttpURLConnection.HTTP_NOT_AUTHORITATIVE
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED
import java.net.HttpURLConnection.HTTP_NO_CONTENT
import java.net.HttpURLConnection.HTTP_OK
import java.net.HttpURLConnection.HTTP_REQ_TOO_LONG
import java.util.Date
import java.util.concurrent.TimeUnit.SECONDS
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http.StatusLine
import okhttp3.internal.http.toHttpDateOrNull
import okhttp3.internal.toNonNegativeInt

/**
 *
 * 缓存策略
 *
 * 这个类决定了是使用缓存还是使用网络，或者都使用
 *
 * Selecting a cache strategy may add conditions to the request (like the "If-Modified-Since" header
 * for conditional GETs) or warnings to the cached response (if the cached data is potentially
 * stale).
 */
class CacheStrategy internal constructor(
        /** 需要发送给服务端的请求，如果使用缓存的话这个值为null。 */
        val networkRequest: Request?,
        /** 要返回或者是发送给服务器进行验证的缓存的response; 如果不适用缓存的话，这个值为null. */
        val cacheResponse: Response?
) {

    class Factory(
            private val nowMillis: Long,
            internal val request: Request,
            private val cacheResponse: Response?
    ) {
        /** The server's time when the cached response was served, if known. */
        private var servedDate: Date? = null //服务器时间
        private var servedDateString: String? = null

        /** 对应头部的`last-modified`. */
        private var lastModified: Date? = null //缓存上次修改时间
        private var lastModifiedString: String? = null

        private var expires: Date? = null //过期的绝对时间，max-age优先

        /**
         * Extension header set by OkHttp specifying the timestamp when the cached HTTP request was
         * first initiated.
         */
        private var sentRequestMillis = 0L //okhttp特殊字段，缓存的HTTP request第一次创建时记录一个时间戳

        /**
         * Extension header set by OkHttp specifying the timestamp when the cached HTTP response was
         * first received.
         */
        private var receivedResponseMillis = 0L //okhttp特殊字段，第一次收到响应时记录一个时间戳

        /** Etag of the cached response. */
        private var etag: String? = null //缓存的标记

        /** Age of the cached response. */
        private var ageSeconds = -1

        /**
         * Returns true if computeFreshnessLifetime used a heuristic. If we used a heuristic to serve a
         * cached response older than 24 hours, we are required to attach a warning.
         */
        private fun isFreshnessLifetimeHeuristic(): Boolean {
            return cacheResponse!!.cacheControl.maxAgeSeconds == -1 && expires == null
        }

        init {
            if (cacheResponse != null) {
                this.sentRequestMillis = cacheResponse.sentRequestAtMillis
                this.receivedResponseMillis = cacheResponse.receivedResponseAtMillis
                val headers = cacheResponse.headers
                for (i in 0 until headers.size) {
                    val fieldName = headers.name(i)
                    val value = headers.value(i)
                    when {
                        fieldName.equals("Date", ignoreCase = true) -> {
                            servedDate = value.toHttpDateOrNull()
                            servedDateString = value
                        }
                        fieldName.equals("Expires", ignoreCase = true) -> {
                            expires = value.toHttpDateOrNull()
                        }
                        fieldName.equals("Last-Modified", ignoreCase = true) -> {
                            lastModified = value.toHttpDateOrNull()
                            lastModifiedString = value
                        }
                        fieldName.equals("ETag", ignoreCase = true) -> {
                            etag = value
                        }
                        fieldName.equals("Age", ignoreCase = true) -> {
                            ageSeconds = value.toNonNegativeInt(-1)
                        }
                    }
                }
            }
        }

        /** Returns a strategy to satisfy [request] using [cacheResponse]. */
        fun compute(): CacheStrategy {
            val candidate = computeCandidate()

            // We're forbidden from using the network and the cache is insufficient.
            //禁止使用网络，并且缓存不可用
            if (candidate.networkRequest != null && request.cacheControl.onlyIfCached) {
                return CacheStrategy(null, null)
            }

            return candidate
        }

        /** 假设可以使用网络，则返回要使用的策略 */
        private fun computeCandidate(): CacheStrategy {
            // 没有缓存
            if (cacheResponse == null) {
                return CacheStrategy(request, null)
            }

            // 如果没有握手的数据，并且是https的请求，直接丢掉缓存，也视为缓存不可用
            if (request.isHttps && cacheResponse.handshake == null) {
                return CacheStrategy(request, null)
            }

            // 如果这个response不应该被缓存, 它就不应该作为新的响应的来源。
            // This check should be redundant as long as the persistence store is well-behaved and the
            // rules are constant.
            if (!isCacheable(cacheResponse, request)) {
                return CacheStrategy(request, null)
            }

            val requestCaching = request.cacheControl
            //客户端设置了不使用缓存
            if (requestCaching.noCache || hasConditions(request)) {
                return CacheStrategy(request, null)
            }

            val responseCaching = cacheResponse.cacheControl

            val ageMillis = cacheResponseAge()
            var freshMillis = computeFreshnessLifetime()
            //计算缓存新鲜度
            if (requestCaching.maxAgeSeconds != -1) {
                freshMillis = minOf(freshMillis, SECONDS.toMillis(requestCaching.maxAgeSeconds.toLong()))
            }

            var minFreshMillis: Long = 0
            if (requestCaching.minFreshSeconds != -1) {
                minFreshMillis = SECONDS.toMillis(requestCaching.minFreshSeconds.toLong())
            }

            var maxStaleMillis: Long = 0
            if (!responseCaching.mustRevalidate && requestCaching.maxStaleSeconds != -1) {
                maxStaleMillis = SECONDS.toMillis(requestCaching.maxStaleSeconds.toLong())
            }
            //服务端没有禁止缓存，并且
            if (!responseCaching.noCache && ageMillis + minFreshMillis < freshMillis + maxStaleMillis) {
                val builder = cacheResponse.newBuilder()
                if (ageMillis + minFreshMillis >= freshMillis) {
                    builder.addHeader("Warning", "110 HttpURLConnection \"Response is stale\"")
                }
                val oneDayMillis = 24 * 60 * 60 * 1000L
                if (ageMillis > oneDayMillis && isFreshnessLifetimeHeuristic()) {
                    builder.addHeader("Warning", "113 HttpURLConnection \"Heuristic expiration\"")
                }
                return CacheStrategy(null, builder.build())
            }

            // 执行有条件的缓存策略
            val conditionName: String
            val conditionValue: String?
            when {
                etag != null -> {
                    conditionName = "If-None-Match"
                    conditionValue = etag
                }

                lastModified != null -> {
                    conditionName = "If-Modified-Since"
                    conditionValue = lastModifiedString
                }

                servedDate != null -> {
                    conditionName = "If-Modified-Since"
                    conditionValue = servedDateString
                }

                else -> return CacheStrategy(request, null) // No condition! Make a regular request.
            }

            val conditionalRequestHeaders = request.headers.newBuilder()
            conditionalRequestHeaders.addLenient(conditionName, conditionValue!!)

            val conditionalRequest = request.newBuilder()
                    .headers(conditionalRequestHeaders.build())
                    .build()
            return CacheStrategy(conditionalRequest, cacheResponse)
        }

        /**
         * Returns the number of milliseconds that the response was fresh for, starting from the served
         * date.
         */
        private fun computeFreshnessLifetime(): Long {
            val responseCaching = cacheResponse!!.cacheControl
            if (responseCaching.maxAgeSeconds != -1) {
                return SECONDS.toMillis(responseCaching.maxAgeSeconds.toLong())
            }

            val expires = this.expires
            if (expires != null) {
                val servedMillis = servedDate?.time ?: receivedResponseMillis
                val delta = expires.time - servedMillis
                return if (delta > 0L) delta else 0L
            }

            if (lastModified != null && cacheResponse.request.url.query == null) {
                // As recommended by the HTTP RFC and implemented in Firefox, the max age of a document
                // should be defaulted to 10% of the document's age at the time it was served. Default
                // expiration dates aren't used for URIs containing a query.
                val servedMillis = servedDate?.time ?: sentRequestMillis
                val delta = servedMillis - lastModified!!.time
                return if (delta > 0L) delta / 10 else 0L
            }

            return 0L
        }

        /**
         * Returns the current age of the response, in milliseconds. The calculation is specified by RFC
         * 7234, 4.2.3 Calculating Age.
         */
        private fun cacheResponseAge(): Long {
            val servedDate = this.servedDate
            val apparentReceivedAge = if (servedDate != null) {
                maxOf(0, receivedResponseMillis - servedDate.time)
            } else {
                0
            }

            val receivedAge = if (ageSeconds != -1) {
                maxOf(apparentReceivedAge, SECONDS.toMillis(ageSeconds.toLong()))
            } else {
                apparentReceivedAge
            }

            val responseDuration = receivedResponseMillis - sentRequestMillis
            val residentDuration = nowMillis - receivedResponseMillis
            return receivedAge + responseDuration + residentDuration
        }

        /**
         * Returns true if the request contains conditions that save the server from sending a response
         * that the client has locally. When a request is enqueued with its own conditions, the built-in
         * response cache won't be used.
         */
        private fun hasConditions(request: Request): Boolean =
                request.header("If-Modified-Since") != null || request.header("If-None-Match") != null
    }

    companion object {
        /** returns true if [response] can be stored to later serve another request.
         * [response]可以被缓存，并且可以用来服务另一个请求则返回true
         */
        fun isCacheable(response: Response, request: Request): Boolean {
            // Always go to network for uncacheable response codes (RFC 7231 section 6.1), This
            // implementation doesn't support caching partial content.

            // 不支持缓存部分内容-分段请求
            when (response.code) {
                HTTP_OK,
                HTTP_NOT_AUTHORITATIVE,
                HTTP_NO_CONTENT,
                HTTP_MULT_CHOICE,
                HTTP_MOVED_PERM,
                HTTP_NOT_FOUND,
                HTTP_BAD_METHOD,
                HTTP_GONE,
                HTTP_REQ_TOO_LONG,
                HTTP_NOT_IMPLEMENTED,
                StatusLine.HTTP_PERM_REDIRECT -> {
                    // These codes can be cached unless headers forbid it.
                    // 如果header中没有明确禁止，上面这些响应码表示response可以被缓存
                }

                HTTP_MOVED_TEMP, //302,307
                StatusLine.HTTP_TEMP_REDIRECT -> {
                    // These codes can only be cached with the right response headers.
                    // http://tools.ietf.org/html/rfc7234#section-3
                    // s-maxage is not checked because OkHttp is a private cache that should ignore s-maxage.
                    if (response.header("Expires") == null &&
                            response.cacheControl.maxAgeSeconds == -1 &&
                            !response.cacheControl.isPublic &&
                            !response.cacheControl.isPrivate) {
                        return false
                    }
                }

                else -> {
                    // All other codes cannot be cached.
                    return false
                }
            }

            // A 'no-store' directive on request or response prevents the response from being cached.
            return !response.cacheControl.noStore && !request.cacheControl.noStore
        }
    }
}
