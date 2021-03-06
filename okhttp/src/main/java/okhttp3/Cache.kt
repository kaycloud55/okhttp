/*
 * Copyright (C) 2010 The Android Open Source Project
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

import java.io.Closeable
import java.io.File
import java.io.Flushable
import java.io.IOException
import java.security.cert.Certificate
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.util.NoSuchElementException
import java.util.TreeSet
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.internal.EMPTY_HEADERS
import okhttp3.internal.cache.CacheRequest
import okhttp3.internal.cache.CacheStrategy
import okhttp3.internal.cache.DiskLruCache
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.http.HttpMethod
import okhttp3.internal.http.StatusLine
import okhttp3.internal.io.FileSystem
import okhttp3.internal.platform.Platform
import okhttp3.internal.toLongOrDefault
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Sink
import okio.Source
import okio.buffer

/**
 * 缓存 HTTP 和 HTTPS 响应文件系统，这样就可以重用它们，节省时间和带宽。
 *
 * ## 缓存优化
 *
 * 为了测量缓存的有效性，追踪了三个指标数据:
 *
 *  * **[请求次数:][requestCount]** 从当前缓存创建开始，发出HTTP请求的次数
 *  * **[请求通过网络的次数:][networkCount]** 上面的那些请求，使用网络的次数
 *  * **[请求命中缓存的次数:][hitCount]** 上面的那些请求，直接从缓存返回的次数
 *
 * 某些情况下，请求可能会导致"有条件的"缓存命中。比如since-not-modified的header，就需要先向服务器验证当前缓存是否可用，
 * 如果服务器返回not modified，就会导致即通过网络发送了请求，也命中了本地缓存，同时会增加[networkCount]和[hitCount]
 *
 * 提高缓存命中率的最佳方法是配置 web 服务器返回可缓存的响应。 尽管这个客户端支持所有 http / 1.1(RFC 7234)缓存头，但它不缓存部分响应。
 *
 * ## 强制通过网络发送请求
 *
 * 某些情况下, 例如用户点击刷新按钮, 这就需要直接跳过缓存，强制从服务端拉取最新数据.
 * 为了强制刷新，一般需要在header里面添加'no-cache'。
 * directive:
 *
 * ```
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder().noCache().build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build();
 * ```
 *
 * 如果只需要强制缓存的响应由服务器进行验证，那么使用效率更高的 max-age 0指令:
 *
 * ```
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder()
 *         .maxAge(0, TimeUnit.SECONDS)
 *         .build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build();
 * ```
 *
 * ## 强制使用缓存
 *
 * 有时候，如果资源是立即可用的，您会希望显示这些资源，但不希望显示其他资源。 这可以用来使应用程序在等待下载最新数据时显示一些内容。
 * 若要将请求限制为本地缓存的资源，请添加 only-if-cache 指令:
 *
 * ```
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder()
 *         .onlyIfCached()
 *         .build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build();
 * Response forceCacheResponse = client.newCall(request).execute();
 * if (forceCacheResponse.code() != 504) {
 *   // The resource was cached! Show it.
 * } else {
 *   // The resource was not cached.
 * }
 * ```
 *
 * 这种技术在一个陈旧的响应比没有响应更好的情况下工作得更好。 要允许缓存过时的响应，请使用 max-stale 指令，并使用以秒为单位的最大过时值:
 *
 * ```
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder()
 *         .maxStale(365, TimeUnit.DAYS)
 *         .build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build();
 * ```
 *
 * Cachecontrol 类可以配置请求缓存指令和解析响应缓存指令。 它甚至提供了方便的常量 CacheControl.FORCE NETWORK 和 CacheControl.FORCE CACHE，这些常量可以解决上面的用例。
 *
 * [rfc_7234]: http://tools.ietf.org/html/rfc7234
 */
class Cache internal constructor(
        directory: File, //缓存的存放路径
        maxSize: Long, //最大的缓存量，达到这个量就要清理
        fileSystem: FileSystem
) : Closeable, Flushable {
    //缓存的核心还是DiskLruCache
    internal val cache = DiskLruCache(
            fileSystem = fileSystem,
            directory = directory,
            appVersion = VERSION,
            valueCount = ENTRY_COUNT,
            maxSize = maxSize,
            taskRunner = TaskRunner.INSTANCE
    )

    // read and write statistics, all guarded by 'this'.
    internal var writeSuccessCount = 0
    internal var writeAbortCount = 0
    private var networkCount = 0
    private var hitCount = 0
    private var requestCount = 0

    val isClosed: Boolean
        get() = cache.isClosed()

    /** Create a cache of at most [maxSize] bytes in [directory]. */
    constructor(directory: File, maxSize: Long) : this(directory, maxSize, FileSystem.SYSTEM)

    internal fun get(request: Request): Response? {
        val key = key(request.url) //缓存命中的key是根据url算出来的
        val snapshot: DiskLruCache.Snapshot = try {
            cache[key] ?: return null //拿出来的是snapshot
        } catch (_: IOException) {
            return null // Give up because the cache cannot be read.
        }

        val entry: Entry = try {
            Entry(snapshot.getSource(ENTRY_METADATA)) //转换成entry
        } catch (_: IOException) {
            snapshot.closeQuietly()
            return null
        }

        val response = entry.response(snapshot) //转换成response
        if (!entry.matches(request, response)) {
            response.body?.closeQuietly()
            return null
        }

        return response
    }

    /**
     * 刷新缓存
     */
    internal fun put(response: Response): CacheRequest? {
        val requestMethod = response.request.method
        //是否要刷新缓存，GET返回false
        if (HttpMethod.invalidatesCache(response.request.method)) {
            try {
                remove(response.request)
            } catch (_: IOException) {
                // The cache cannot be written.
            }
            return null
        }

        if (requestMethod != "GET") {
            // 不缓存除GET请求以外的响应；从技术上来说，HEAD和POST也是可以缓存的，但是成本太高，收益太低
            return null
        }
        //header中有*号的不允许缓存
        if (response.hasVaryAll()) {
            return null
        }

        val entry = Entry(response)
        var editor: DiskLruCache.Editor? = null
        try {
            editor = cache.edit(key(response.request.url)) ?: return null
            entry.writeTo(editor)
            return RealCacheRequest(editor)
        } catch (_: IOException) {
            abortQuietly(editor)
            return null
        }
    }

    @Throws(IOException::class)
    internal fun remove(request: Request) {
        cache.remove(key(request.url))
    }

    /**
     * 更新缓存
     */
    internal fun update(cached: Response, network: Response) {
        val entry = Entry(network)
        val snapshot = (cached.body as CacheResponseBody).snapshot
        var editor: DiskLruCache.Editor? = null
        try {
            editor = snapshot.edit() ?: return // edit() returns null if snapshot is not current.
            entry.writeTo(editor)
            editor.commit()
        } catch (_: IOException) {
            abortQuietly(editor)
        }
    }

    private fun abortQuietly(editor: DiskLruCache.Editor?) {
        // Give up because the cache cannot be written.
        try {
            editor?.abort()
        } catch (_: IOException) {
        }
    }

    /**
     * Initialize the cache. This will include reading the journal files from the storage and building
     * up the necessary in-memory cache information.
     *
     * 这个方法将会读取磁盘缓存来建立必要的内存缓存，这样响应速度会更快。
     *
     *
     * 根据缓存文件大小和当前的时机缓存大小，初始化时间可能会有所不同。应用程序需要在初始化阶段调用这个函数，并在是在后台线程中调用。
     *
     * 应用程序不会主动调用这个方法来初始化。当OKhttp第一次使用cache的时候，会主动调用这个方法。
     */
    @Throws(IOException::class)
    fun initialize() {
        cache.initialize()
    }

    /**
     * 关闭缓存并删除所有的缓存文件
     */
    @Throws(IOException::class)
    fun delete() {
        cache.delete()
    }

    /**
     * 删除存储在缓存中的所有值. 对缓存的动态写操作将正常完成，但不会存储相应的响应.
     */
    @Throws(IOException::class)
    fun evictAll() {
        cache.evictAll()
    }

    /**
     * 返回对此缓存中的 url 的迭代器。 这个迭代器不会抛出 ConcurrentModificationException，
     * 但是如果在迭代过程中添加了新响应，它们的 url 将不会返回。 如果现有的响应在迭代期间被驱逐，那么它们将不存在(除非它们已经被返回)。
     *
     * The iterator supports [MutableIterator.remove]. Removing a URL from the iterator evicts the
     * corresponding response from the cache. Use this to evict selected responses.
     */
    @Throws(IOException::class)
    fun urls(): MutableIterator<String> {
        return object : MutableIterator<String> {
            val delegate: MutableIterator<DiskLruCache.Snapshot> = cache.snapshots()
            var nextUrl: String? = null
            var canRemove = false

            override fun hasNext(): Boolean {
                if (nextUrl != null) return true

                canRemove = false // Prevent delegate.remove() on the wrong item!
                while (delegate.hasNext()) {
                    try {
                        delegate.next().use { snapshot ->
                            val metadata = snapshot.getSource(ENTRY_METADATA).buffer()
                            nextUrl = metadata.readUtf8LineStrict()
                            return true
                        }
                    } catch (_: IOException) {
                        // We couldn't read the metadata for this snapshot; possibly because the host filesystem
                        // has disappeared! Skip it.
                    }
                }

                return false
            }

            override fun next(): String {
                if (!hasNext()) throw NoSuchElementException()
                val result = nextUrl!!
                nextUrl = null
                canRemove = true
                return result
            }

            override fun remove() {
                check(canRemove) { "remove() before next()" }
                delegate.remove()
            }
        }
    }

    @Synchronized
    fun writeAbortCount(): Int = writeAbortCount

    @Synchronized
    fun writeSuccessCount(): Int = writeSuccessCount

    @Throws(IOException::class)
    fun size(): Long = cache.size()

    /** Max size of the cache (in bytes). */
    fun maxSize(): Long = cache.maxSize

    @Throws(IOException::class)
    override fun flush() {
        cache.flush()
    }

    @Throws(IOException::class)
    override fun close() {
        cache.close()
    }

    @get:JvmName("directory")
    val directory: File
        get() = cache.directory

    @JvmName("-deprecated_directory")
    @Deprecated(
            message = "moved to val",
            replaceWith = ReplaceWith(expression = "directory"),
            level = DeprecationLevel.ERROR)
    fun directory(): File = cache.directory

    /**
     * 追踪缓存命中比例，体现在网络请求次数和命中次数
     */
    @Synchronized
    internal fun trackResponse(cacheStrategy: CacheStrategy) {
        requestCount++

        if (cacheStrategy.networkRequest != null) {
            // If this is a conditional request, we'll increment hitCount if/when it hits.
            networkCount++
        } else if (cacheStrategy.cacheResponse != null) {
            // This response uses the cache and not the network. That's a cache hit.
            hitCount++
        }
    }

    @Synchronized
    internal fun trackConditionalCacheHit() {
        hitCount++
    }

    @Synchronized
    fun networkCount(): Int = networkCount

    @Synchronized
    fun hitCount(): Int = hitCount

    @Synchronized
    fun requestCount(): Int = requestCount

    private inner class RealCacheRequest internal constructor(
            private val editor: DiskLruCache.Editor
    ) : CacheRequest {
        private val cacheOut: Sink = editor.newSink(ENTRY_BODY)
        private val body: Sink
        internal var done = false

        init {
            this.body = object : ForwardingSink(cacheOut) {
                @Throws(IOException::class)
                override fun close() {
                    synchronized(this@Cache) {
                        if (done) return
                        done = true
                        writeSuccessCount++
                    }
                    super.close()
                    editor.commit()
                }
            }
        }

        override fun abort() {
            synchronized(this@Cache) {
                if (done) return
                done = true
                writeAbortCount++
            }
            cacheOut.closeQuietly()
            try {
                editor.abort()
            } catch (_: IOException) {
            }
        }

        override fun body(): Sink = body
    }

    private class Entry {
        private val url: String
        private val varyHeaders: Headers
        private val requestMethod: String
        private val protocol: Protocol
        private val code: Int
        private val message: String
        private val responseHeaders: Headers
        private val handshake: Handshake?
        private val sentRequestMillis: Long
        private val receivedResponseMillis: Long

        private val isHttps: Boolean get() = url.startsWith("https://")

        /**
         * Reads an entry from an input stream. A typical entry looks like this:
         *
         * ```
         * http://google.com/foo
         * GET
         * 2
         * Accept-Language: fr-CA
         * Accept-Charset: UTF-8
         * HTTP/1.1 200 OK
         * 3
         * Content-Type: image/png
         * Content-Length: 100
         * Cache-Control: max-age=600
         * ```
         *
         * A typical HTTPS file looks like this:
         *
         * ```
         * https://google.com/foo
         * GET
         * 2
         * Accept-Language: fr-CA
         * Accept-Charset: UTF-8
         * HTTP/1.1 200 OK
         * 3
         * Content-Type: image/png
         * Content-Length: 100
         * Cache-Control: max-age=600
         *
         * AES_256_WITH_MD5
         * 2
         * base64-encoded peerCertificate[0]
         * base64-encoded peerCertificate[1]
         * -1
         * TLSv1.2
         * ```
         *
         * The file is newline separated. The first two lines are the URL and the request method. Next
         * is the number of HTTP Vary request header lines, followed by those lines.
         *
         * Next is the response status line, followed by the number of HTTP response header lines,
         * followed by those lines.
         *
         * HTTPS responses also contain SSL session information. This begins with a blank line, and then
         * a line containing the cipher suite. Next is the length of the peer certificate chain. These
         * certificates are base64-encoded and appear each on their own line. The next line contains the
         * length of the local certificate chain. These certificates are also base64-encoded and appear
         * each on their own line. A length of -1 is used to encode a null array. The last line is
         * optional. If present, it contains the TLS version.
         */
        @Throws(IOException::class)
        internal constructor(rawSource: Source) {
            try {
                val source = rawSource.buffer()
                url = source.readUtf8LineStrict()
                requestMethod = source.readUtf8LineStrict()
                val varyHeadersBuilder = Headers.Builder()
                val varyRequestHeaderLineCount = readInt(source)
                for (i in 0 until varyRequestHeaderLineCount) {
                    varyHeadersBuilder.addLenient(source.readUtf8LineStrict())
                }
                varyHeaders = varyHeadersBuilder.build()

                val statusLine = StatusLine.parse(source.readUtf8LineStrict())
                protocol = statusLine.protocol
                code = statusLine.code
                message = statusLine.message
                val responseHeadersBuilder = Headers.Builder()
                val responseHeaderLineCount = readInt(source)
                for (i in 0 until responseHeaderLineCount) {
                    responseHeadersBuilder.addLenient(source.readUtf8LineStrict())
                }
                val sendRequestMillisString = responseHeadersBuilder[SENT_MILLIS]
                val receivedResponseMillisString = responseHeadersBuilder[RECEIVED_MILLIS]
                responseHeadersBuilder.removeAll(SENT_MILLIS)
                responseHeadersBuilder.removeAll(RECEIVED_MILLIS)
                sentRequestMillis = sendRequestMillisString?.toLong() ?: 0L
                receivedResponseMillis = receivedResponseMillisString?.toLong() ?: 0L
                responseHeaders = responseHeadersBuilder.build()

                if (isHttps) {
                    val blank = source.readUtf8LineStrict()
                    if (blank.isNotEmpty()) {
                        throw IOException("expected \"\" but was \"$blank\"")
                    }
                    val cipherSuiteString = source.readUtf8LineStrict()
                    val cipherSuite = CipherSuite.forJavaName(cipherSuiteString)
                    val peerCertificates = readCertificateList(source)
                    val localCertificates = readCertificateList(source)
                    val tlsVersion = if (!source.exhausted()) {
                        TlsVersion.forJavaName(source.readUtf8LineStrict())
                    } else {
                        TlsVersion.SSL_3_0
                    }
                    handshake = Handshake.get(tlsVersion, cipherSuite, peerCertificates, localCertificates)
                } else {
                    handshake = null
                }
            } finally {
                rawSource.close()
            }
        }

        internal constructor(response: Response) {
            this.url = response.request.url.toString()
            this.varyHeaders = response.varyHeaders()
            this.requestMethod = response.request.method
            this.protocol = response.protocol
            this.code = response.code
            this.message = response.message
            this.responseHeaders = response.headers
            this.handshake = response.handshake
            this.sentRequestMillis = response.sentRequestAtMillis
            this.receivedResponseMillis = response.receivedResponseAtMillis
        }

        @Throws(IOException::class)
        fun writeTo(editor: DiskLruCache.Editor) {
            editor.newSink(ENTRY_METADATA).buffer().use { sink ->
                sink.writeUtf8(url).writeByte('\n'.toInt())
                sink.writeUtf8(requestMethod).writeByte('\n'.toInt())
                sink.writeDecimalLong(varyHeaders.size.toLong()).writeByte('\n'.toInt())
                for (i in 0 until varyHeaders.size) {
                    sink.writeUtf8(varyHeaders.name(i))
                            .writeUtf8(": ")
                            .writeUtf8(varyHeaders.value(i))
                            .writeByte('\n'.toInt())
                }

                sink.writeUtf8(StatusLine(protocol, code, message).toString()).writeByte('\n'.toInt())
                sink.writeDecimalLong((responseHeaders.size + 2).toLong()).writeByte('\n'.toInt())
                for (i in 0 until responseHeaders.size) {
                    sink.writeUtf8(responseHeaders.name(i))
                            .writeUtf8(": ")
                            .writeUtf8(responseHeaders.value(i))
                            .writeByte('\n'.toInt())
                }
                sink.writeUtf8(SENT_MILLIS)
                        .writeUtf8(": ")
                        .writeDecimalLong(sentRequestMillis)
                        .writeByte('\n'.toInt())
                sink.writeUtf8(RECEIVED_MILLIS)
                        .writeUtf8(": ")
                        .writeDecimalLong(receivedResponseMillis)
                        .writeByte('\n'.toInt())

                if (isHttps) {
                    sink.writeByte('\n'.toInt())
                    sink.writeUtf8(handshake!!.cipherSuite.javaName).writeByte('\n'.toInt())
                    writeCertList(sink, handshake.peerCertificates)
                    writeCertList(sink, handshake.localCertificates)
                    sink.writeUtf8(handshake.tlsVersion.javaName).writeByte('\n'.toInt())
                }
            }
        }

        @Throws(IOException::class)
        private fun readCertificateList(source: BufferedSource): List<Certificate> {
            val length = readInt(source)
            if (length == -1) return emptyList() // OkHttp v1.2 used -1 to indicate null.

            try {
                val certificateFactory = CertificateFactory.getInstance("X.509")
                val result = ArrayList<Certificate>(length)
                for (i in 0 until length) {
                    val line = source.readUtf8LineStrict()
                    val bytes = Buffer()
                    bytes.write(line.decodeBase64()!!)
                    result.add(certificateFactory.generateCertificate(bytes.inputStream()))
                }
                return result
            } catch (e: CertificateException) {
                throw IOException(e.message)
            }
        }

        @Throws(IOException::class)
        private fun writeCertList(sink: BufferedSink, certificates: List<Certificate>) {
            try {
                sink.writeDecimalLong(certificates.size.toLong()).writeByte('\n'.toInt())
                for (i in 0 until certificates.size) {
                    val bytes = certificates[i].encoded
                    val line = bytes.toByteString().base64()
                    sink.writeUtf8(line).writeByte('\n'.toInt())
                }
            } catch (e: CertificateEncodingException) {
                throw IOException(e.message)
            }
        }

        fun matches(request: Request, response: Response): Boolean {
            return url == request.url.toString() &&
                    requestMethod == request.method &&
                    varyMatches(response, varyHeaders, request)
        }

        fun response(snapshot: DiskLruCache.Snapshot): Response {
            val contentType = responseHeaders["Content-Type"]
            val contentLength = responseHeaders["Content-Length"]
            val cacheRequest = Request.Builder()
                    .url(url)
                    .method(requestMethod, null)
                    .headers(varyHeaders)
                    .build()
            return Response.Builder()
                    .request(cacheRequest)
                    .protocol(protocol)
                    .code(code)
                    .message(message)
                    .headers(responseHeaders)
                    .body(CacheResponseBody(snapshot, contentType, contentLength))
                    .handshake(handshake)
                    .sentRequestAtMillis(sentRequestMillis)
                    .receivedResponseAtMillis(receivedResponseMillis)
                    .build()
        }

        companion object {
            /** Synthetic response header: the local time when the request was sent. */
            private val SENT_MILLIS = "${Platform.get().getPrefix()}-Sent-Millis"

            /** Synthetic response header: the local time when the response was received. */
            private val RECEIVED_MILLIS = "${Platform.get().getPrefix()}-Received-Millis"
        }
    }

    private class CacheResponseBody internal constructor(
            internal val snapshot: DiskLruCache.Snapshot,
            private val contentType: String?,
            private val contentLength: String?
    ) : ResponseBody() {
        private val bodySource: BufferedSource

        init {
            val source = snapshot.getSource(ENTRY_BODY)
            bodySource = object : ForwardingSource(source) {
                @Throws(IOException::class)
                override fun close() {
                    snapshot.close()
                    super.close()
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = contentType?.toMediaTypeOrNull()

        override fun contentLength(): Long = contentLength?.toLongOrDefault(-1L) ?: -1L

        override fun source(): BufferedSource = bodySource
    }

    companion object {
        private const val VERSION = 201105
        private const val ENTRY_METADATA = 0
        private const val ENTRY_BODY = 1
        private const val ENTRY_COUNT = 2

        @JvmStatic
        fun key(url: HttpUrl): String = url.toString().encodeUtf8().md5().hex()

        @Throws(IOException::class)
        internal fun readInt(source: BufferedSource): Int {
            try {
                val result = source.readDecimalLong()
                val line = source.readUtf8LineStrict()
                if (result < 0L || result > Integer.MAX_VALUE || line.isNotEmpty()) {
                    throw IOException("expected an int but was \"$result$line\"")
                }
                return result.toInt()
            } catch (e: NumberFormatException) {
                throw IOException(e.message)
            }
        }

        /**
         * Returns true if none of the Vary headers have changed between [cachedRequest] and
         * [newRequest].
         */
        fun varyMatches(
                cachedResponse: Response,
                cachedRequest: Headers,
                newRequest: Request
        ): Boolean {
            return cachedResponse.headers.varyFields().none {
                cachedRequest.values(it) != newRequest.headers(it)
            }
        }

        /** Returns true if a Vary header contains an asterisk. Such responses cannot be cached. */
        fun Response.hasVaryAll() = "*" in headers.varyFields()

        /**
         * Returns the names of the request headers that need to be checked for equality when caching.
         */
        private fun Headers.varyFields(): Set<String> {
            var result: MutableSet<String>? = null
            for (i in 0 until size) {
                if (!"Vary".equals(name(i), ignoreCase = true)) {
                    continue
                }

                val value = value(i)
                if (result == null) {
                    result = TreeSet(String.CASE_INSENSITIVE_ORDER)
                }
                for (varyField in value.split(',')) {
                    result.add(varyField.trim())
                }
            }
            return result ?: emptySet()
        }

        /**
         * Returns the subset of the headers in this's request that impact the content of this's body.
         */
        fun Response.varyHeaders(): Headers {
            // Use the request headers sent over the network, since that's what the response varies on.
            // Otherwise OkHttp-supplied headers like "Accept-Encoding: gzip" may be lost.
            val requestHeaders = networkResponse!!.request.headers
            val responseHeaders = headers
            return varyHeaders(requestHeaders, responseHeaders)
        }

        /**
         * Returns the subset of the headers in [requestHeaders] that impact the content of the
         * response's body.
         */
        private fun varyHeaders(requestHeaders: Headers, responseHeaders: Headers): Headers {
            val varyFields = responseHeaders.varyFields()
            if (varyFields.isEmpty()) return EMPTY_HEADERS

            val result = Headers.Builder()
            for (i in 0 until requestHeaders.size) {
                val fieldName = requestHeaders.name(i)
                if (fieldName in varyFields) {
                    result.add(fieldName, requestHeaders.value(i))
                }
            }
            return result.build()
        }
    }
}
