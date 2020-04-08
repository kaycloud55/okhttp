/*
 * Copyright (C) 2019 Square, Inc.
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

import java.util.concurrent.TimeUnit
import okhttp3.internal.indexOfNonWhitespace
import okhttp3.internal.toNonNegativeInt

/**
 * 来自服务器或客户端的带有缓存指令的缓存控制头。 这些指令就可以存储哪些响应以及这些存储的响应可以满足哪些请求设置策略。
 *
 * See [RFC 7234, 5.2](https://tools.ietf.org/html/rfc7234#section-5.2).
 */
class CacheControl private constructor(
        /**
         * 'no-cache'这个头字段其实是带有误导信的。它并不阻止客户端缓存响应; 它仅仅只是意味着用之前要先去服务端进行验证.
         * 我们可以通过这个字段来有条件的GET请求。
         *
         * 在请求中，它表示不使用缓存来响应请求。
         */
        @get:JvmName("noCache") val noCache: Boolean,

        /** 如果为true，响应不应该被缓存. */
        @get:JvmName("noStore") val noStore: Boolean,

        /** 在这个时间内不需要去服务端验证，可以直接使用 */
        @get:JvmName("maxAgeSeconds") val maxAgeSeconds: Int,

        /**
         * “ s-maxage”指令是共享缓存的最大年龄。 不要与非共享缓存的“ max-age”混淆，在 Firefox 和 Chrome 中，这个缓存不遵守这个指令。
         */
        @get:JvmName("sMaxAgeSeconds") val sMaxAgeSeconds: Int,

        val isPrivate: Boolean,
        val isPublic: Boolean,

        @get:JvmName("mustRevalidate") val mustRevalidate: Boolean,

        @get:JvmName("maxStaleSeconds") val maxStaleSeconds: Int,

        @get:JvmName("minFreshSeconds") val minFreshSeconds: Int,

        /**
         * “only-if-cached"字段也是带有误导性的。它实际上是”不要使用网络“的意思。它由客户端设置，客户端只希望在缓存能够完全满足请求的情况返回响应结果，否则返回503

         */
        @get:JvmName("onlyIfCached") val onlyIfCached: Boolean,

        @get:JvmName("noTransform") val noTransform: Boolean,

        @get:JvmName("immutable") val immutable: Boolean,

        private var headerValue: String?
) {
//    @JvmName("-deprecated_noCache")
//    @Deprecated(
//            message = "moved to val",
//            replaceWith = ReplaceWith(expression = "noCache"),
//            level = DeprecationLevel.ERROR)
//    fun noCache() = noCache
//
//    @JvmName("-deprecated_noStore")
//    @Deprecated(
//            message = "moved to val",
//            replaceWith = ReplaceWith(expression = "noStore"),
//            level = DeprecationLevel.ERROR)
//    fun noStore() = noStore
//
//    @JvmName("-deprecated_maxAgeSeconds")
//    @Deprecated(
//            message = "moved to val",
//            replaceWith = ReplaceWith(expression = "maxAgeSeconds"),
//            level = DeprecationLevel.ERROR)
//    fun maxAgeSeconds() = maxAgeSeconds
//
//    @JvmName("-deprecated_sMaxAgeSeconds")
//    @Deprecated(
//            message = "moved to val",
//            replaceWith = ReplaceWith(expression = "sMaxAgeSeconds"),
//            level = DeprecationLevel.ERROR)
//    fun sMaxAgeSeconds() = sMaxAgeSeconds
//
//    @JvmName("-deprecated_mustRevalidate")
//    @Deprecated(
//            message = "moved to val",
//            replaceWith = ReplaceWith(expression = "mustRevalidate"),
//            level = DeprecationLevel.ERROR)
//    fun mustRevalidate() = mustRevalidate
//
//    @JvmName("-deprecated_maxStaleSeconds")
//    @Deprecated(
//            message = "moved to val",
//            replaceWith = ReplaceWith(expression = "maxStaleSeconds"),
//            level = DeprecationLevel.ERROR)
//    fun maxStaleSeconds() = maxStaleSeconds
//
//    @JvmName("-deprecated_minFreshSeconds")
//    @Deprecated(
//            message = "moved to val",
//            replaceWith = ReplaceWith(expression = "minFreshSeconds"),
//            level = DeprecationLevel.ERROR)
//    fun minFreshSeconds() = minFreshSeconds
//
//    @JvmName("-deprecated_onlyIfCached")
//    @Deprecated(
//            message = "moved to val",
//            replaceWith = ReplaceWith(expression = "onlyIfCached"),
//            level = DeprecationLevel.ERROR)
//    fun onlyIfCached() = onlyIfCached
//
//    @JvmName("-deprecated_noTransform")
//    @Deprecated(
//            message = "moved to val",
//            replaceWith = ReplaceWith(expression = "noTransform"),
//            level = DeprecationLevel.ERROR)
//    fun noTransform() = noTransform
//
//    @JvmName("-deprecated_immutable")
//    @Deprecated(
//            message = "moved to val",
//            replaceWith = ReplaceWith(expression = "immutable"),
//            level = DeprecationLevel.ERROR)
//    fun immutable() = immutable

    override fun toString(): String {
        var result = headerValue
        if (result == null) {
            result = buildString {
                if (noCache) append("no-cache, ")
                if (noStore) append("no-store, ")
                if (maxAgeSeconds != -1) append("max-age=").append(maxAgeSeconds).append(", ")
                if (sMaxAgeSeconds != -1) append("s-maxage=").append(sMaxAgeSeconds).append(", ")
                if (isPrivate) append("private, ")
                if (isPublic) append("public, ")
                if (mustRevalidate) append("must-revalidate, ")
                if (maxStaleSeconds != -1) append("max-stale=").append(maxStaleSeconds).append(", ")
                if (minFreshSeconds != -1) append("min-fresh=").append(minFreshSeconds).append(", ")
                if (onlyIfCached) append("only-if-cached, ")
                if (noTransform) append("no-transform, ")
                if (immutable) append("immutable, ")
                if (isEmpty()) return ""
                delete(length - 2, length)
            }
            headerValue = result
        }
        return result
    }

    /** Builds a `Cache-Control` request header. */
    class Builder {
        private var noCache: Boolean = false
        private var noStore: Boolean = false
        private var maxAgeSeconds = -1
        private var maxStaleSeconds = -1
        private var minFreshSeconds = -1
        private var onlyIfCached: Boolean = false
        private var noTransform: Boolean = false
        private var immutable: Boolean = false

        /** 不接受任何未经验证的缓存响应 */
        fun noCache() = apply {
            this.noCache = true
        }

        /** 不存储服务端的响应 */
        fun noStore() = apply {
            this.noStore = true
        }

        /**
         * 缓存的响应的最大存活时间. 如果超过了这个时间，缓存就不能再用了，需要发起网络请求。
         *
         * @param maxAge a non-negative integer. This is stored and transmitted with [TimeUnit.SECONDS]
         *     precision; finer precision will be lost.
         */
        fun maxAge(maxAge: Int, timeUnit: TimeUnit) = apply {
            require(maxAge >= 0) { "maxAge < 0: $maxAge" }
            val maxAgeSecondsLong = timeUnit.toSeconds(maxAge.toLong())
            this.maxAgeSeconds = maxAgeSecondsLong.clampToInt()
        }

        /**
         * 表示客户端愿意接受超过有效期[maxStale]时间之内的响应.
         *
         * @param maxStale a non-negative integer. This is stored and transmitted with
         *     [TimeUnit.SECONDS] precision; finer precision will be lost.
         */
        fun maxStale(maxStale: Int, timeUnit: TimeUnit) = apply {
            require(maxStale >= 0) { "maxStale < 0: $maxStale" }
            val maxStaleSecondsLong = timeUnit.toSeconds(maxStale.toLong())
            this.maxStaleSeconds = maxStaleSecondsLong.clampToInt()
        }

        /**
         * 设置响应的最短有效时间。过了这个时间之后缓存就无效了，需要发起网络请求
         *
         * @param minFresh a non-negative integer. This is stored and transmitted with
         *     [TimeUnit.SECONDS] precision; finer precision will be lost.
         */
        fun minFresh(minFresh: Int, timeUnit: TimeUnit) = apply {
            require(minFresh >= 0) { "minFresh < 0: $minFresh" }
            val minFreshSecondsLong = timeUnit.toSeconds(minFresh.toLong())
            this.minFreshSeconds = minFreshSecondsLong.clampToInt()
        }

        /**
         * 只接受缓存中能提供的响应。 如果缓存不能满足，就返回504
         */
        fun onlyIfCached() = apply {
            this.onlyIfCached = true
        }

        /** 不接受转换后 的响应. */
        fun noTransform() = apply {
            this.noTransform = true
        }

        fun immutable() = apply {
            this.immutable = true
        }

        private fun Long.clampToInt(): Int {
            return when {
                this > Integer.MAX_VALUE -> Integer.MAX_VALUE
                else -> toInt()
            }
        }

        fun build(): CacheControl {
            return CacheControl(noCache, noStore, maxAgeSeconds, -1, false, false, false, maxStaleSeconds,
                    minFreshSeconds, onlyIfCached, noTransform, immutable, null)
        }
    }

    companion object {
        /**
         * Cache control request directives that require network validation of responses. Note that such
         * requests may be assisted by the cache via conditional GET requests.
         */
        @JvmField
        val FORCE_NETWORK = Builder()
                .noCache() //强制请求网络，对应refresh等操作
                .build()

        /**
         * Cache control request directives that uses the cache only, even if the cached response is
         * stale. If the response isn't available in the cache or requires server validation, the call
         * will fail with a `504 Unsatisfiable Request`.
         */
        @JvmField
        val FORCE_CACHE = Builder()
                .onlyIfCached() //强制使用cache
                .maxStale(Integer.MAX_VALUE, TimeUnit.SECONDS)
                .build()

        /**
         * Returns the cache directives of [headers]. This honors both Cache-Control and Pragma headers
         * if they are present.
         */
        @JvmStatic
        fun parse(headers: Headers): CacheControl {
            var noCache = false
            var noStore = false
            var maxAgeSeconds = -1
            var sMaxAgeSeconds = -1
            var isPrivate = false
            var isPublic = false
            var mustRevalidate = false
            var maxStaleSeconds = -1
            var minFreshSeconds = -1
            var onlyIfCached = false
            var noTransform = false
            var immutable = false

            var canUseHeaderValue = true
            var headerValue: String? = null

            loop@ for (i in 0 until headers.size) {
                val name = headers.name(i)
                val value = headers.value(i)

                when {
                    name.equals("Cache-Control", ignoreCase = true) -> {
                        if (headerValue != null) {
                            // Multiple cache-control headers means we can't use the raw value.
                            canUseHeaderValue = false
                        } else {
                            headerValue = value
                        }
                    }
                    name.equals("Pragma", ignoreCase = true) -> {
                        // Might specify additional cache-control params. We invalidate just in case.
                        canUseHeaderValue = false
                    }
                    else -> {
                        continue@loop
                    }
                }

                var pos = 0
                while (pos < value.length) {
                    val tokenStart = pos
                    pos = value.indexOfElement("=,;", pos)
                    val directive = value.substring(tokenStart, pos).trim()
                    val parameter: String?

                    if (pos == value.length || value[pos] == ',' || value[pos] == ';') {
                        pos++ // Consume ',' or ';' (if necessary).
                        parameter = null
                    } else {
                        pos++ // Consume '='.
                        pos = value.indexOfNonWhitespace(pos)

                        if (pos < value.length && value[pos] == '\"') {
                            // Quoted string.
                            pos++ // Consume '"' open quote.
                            val parameterStart = pos
                            pos = value.indexOf('"', pos)
                            parameter = value.substring(parameterStart, pos)
                            pos++ // Consume '"' close quote (if necessary).
                        } else {
                            // Unquoted string.
                            val parameterStart = pos
                            pos = value.indexOfElement(",;", pos)
                            parameter = value.substring(parameterStart, pos).trim()
                        }
                    }

                    when {
                        "no-cache".equals(directive, ignoreCase = true) -> {
                            noCache = true
                        }
                        "no-store".equals(directive, ignoreCase = true) -> {
                            noStore = true
                        }
                        "max-age".equals(directive, ignoreCase = true) -> {
                            maxAgeSeconds = parameter.toNonNegativeInt(-1)
                        }
                        "s-maxage".equals(directive, ignoreCase = true) -> {
                            sMaxAgeSeconds = parameter.toNonNegativeInt(-1)
                        }
                        "private".equals(directive, ignoreCase = true) -> {
                            isPrivate = true
                        }
                        "public".equals(directive, ignoreCase = true) -> {
                            isPublic = true
                        }
                        "must-revalidate".equals(directive, ignoreCase = true) -> {
                            mustRevalidate = true
                        }
                        "max-stale".equals(directive, ignoreCase = true) -> {
                            maxStaleSeconds = parameter.toNonNegativeInt(Integer.MAX_VALUE)
                        }
                        "min-fresh".equals(directive, ignoreCase = true) -> {
                            minFreshSeconds = parameter.toNonNegativeInt(-1)
                        }
                        "only-if-cached".equals(directive, ignoreCase = true) -> {
                            onlyIfCached = true
                        }
                        "no-transform".equals(directive, ignoreCase = true) -> {
                            noTransform = true
                        }
                        "immutable".equals(directive, ignoreCase = true) -> {
                            immutable = true
                        }
                    }
                }
            }

            if (!canUseHeaderValue) {
                headerValue = null
            }

            return CacheControl(noCache, noStore, maxAgeSeconds, sMaxAgeSeconds, isPrivate, isPublic,
                    mustRevalidate, maxStaleSeconds, minFreshSeconds, onlyIfCached, noTransform, immutable,
                    headerValue)
        }

        /**
         * Returns the next index in this at or after [startIndex] that is a character from
         * [characters]. Returns the input length if none of the requested characters can be found.
         */
        private fun String.indexOfElement(characters: String, startIndex: Int = 0): Int {
            for (i in startIndex until length) {
                if (this[i] in characters) {
                    return i
                }
            }
            return length
        }
    }
}
