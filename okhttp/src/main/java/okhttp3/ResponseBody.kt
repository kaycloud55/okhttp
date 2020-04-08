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
package okhttp3

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import kotlin.text.Charsets.UTF_8
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.internal.closeQuietly
import okhttp3.internal.readBomAsCharset
import okio.Buffer
import okio.BufferedSource
import okio.ByteString

/**
 * A one-shot stream from the origin server to the client application with the raw bytes of the
 * response body. Each response body is supported by an active connection to the webserver. This
 * imposes both obligations and limits on the client application.
 *
 * ### response body必须被及时关闭.
 *
 * 每个响应都是由有限的资源例如socket或者是打开的文件（cache）来提供的，如果不及时关闭的话，将会导致资源泄露，导致应用程序变慢甚至crash。
 *
 * Both this class and [Response] implement [Closeable]. Closing a response simply
 * closes its response body. If you invoke [Call.execute] or implement [Callback.onResponse] you
 * must close this body by calling any of the following methods:
 *
 * * `Response.close()`
 * * `Response.body().close()`
 * * `Response.body().source().close()`
 * * `Response.body().charStream().close()`
 * * `Response.body().byteStream().close()`
 * * `Response.body().bytes()`
 * * `Response.body().string()`
 *
 * 对同一个response body多次调用'close()'是没有意义的。
 *
 * 对于同步请求，最简单的确保response body关闭的办法是使用try{}语句块. 编译器会自动插入'finally'语句块，并且执行close。
 *
 * ```
 * Call call = client.newCall(request);
 * try (Response response = call.execute()) {
 * ... // Use the response.
 * }
 * ```
 *
 * 同样的，异步请求也可以这么调用:
 *
 * ```
 * Call call = client.newCall(request);
 * call.enqueue(new Callback() {
 *   public void onResponse(Call call, Response response) throws IOException {
 *     try (ResponseBody responseBody = response.body()) {
 *     ... // Use the response.
 *     }
 *   }
 *
 *   public void onFailure(Call call, IOException e) {
 *   ... // Handle the failure.
 *   }
 * });
 * ```
 *
 *
 * 如果是在异步线程读取响应的话，上面的设置是无效的。必须在读取的线程手动执行[close]操作。
 *
 * ### response body只能被消费一次
 *
 * 当前类可能包含的响应的内容是非常庞大的. 甚至，它可能比当前进程被分配的可用内存还要大，设置比当前设置的内存还要大。
 *
 * 因为此类不缓冲内存中的完整响应，所以应用程序可能不会重新读取响应的字节。
 * 使用这一个快照读取整个响应到内存字节或字符串通过[bytes]或者[string]。 或者使用[source]、 [byteStream] 或 [charStream] 传输响应。
 */
abstract class ResponseBody : Closeable {
    /** Multiple calls to [charStream] must return the same instance. */
    private var reader: Reader? = null

    abstract fun contentType(): MediaType?

    /**
     * Returns the number of bytes in that will returned by [bytes], or [byteStream], or -1 if
     * unknown.
     */
    abstract fun contentLength(): Long

    fun byteStream(): InputStream = source().inputStream()

    abstract fun source(): BufferedSource

    /**
     * 以字节数组的形式返回响应.
     *
     * 这个方法会把整个响应加载到内存中. 如果响应非常大的话，可能会触发[OutOfMemoryError].
     * 所以尽可能考虑使用`stream`。
     */
    @Throws(IOException::class)
    fun bytes() = consumeSource(BufferedSource::readByteArray) { it.size }

    /**
     * 以[ByteString]的形式返回响应.
     *
     * 这个方法会把整个响应加载到内存中. 如果响应非常大的话，可能会触发[OutOfMemoryError].
     */
    @Throws(IOException::class)
    fun byteString() = consumeSource(BufferedSource::readByteString) { it.size }

    private inline fun <T : Any> consumeSource(
            consumer: (BufferedSource) -> T,
            sizeMapper: (T) -> Int
    ): T {
        val contentLength = contentLength()
        if (contentLength > Int.MAX_VALUE) {
            throw IOException("Cannot buffer entire body for content length: $contentLength")
        }

        val bytes = source().use(consumer)
        val size = sizeMapper(bytes)
        if (contentLength != -1L && contentLength != size.toLong()) {
            throw IOException("Content-Length ($contentLength) and stream length ($size) disagree")
        }
        return bytes
    }

    /**
     * 以字节流的形式返回数据
     *
     * If the response starts with a
     * [Byte Order Mark (BOM)](https://en.wikipedia.org/wiki/Byte_order_mark), it is consumed and
     * used to determine the charset of the response bytes.
     *
     * Otherwise if the response has a `Content-Type` header that specifies a charset, that is used
     * to determine the charset of the response bytes.
     *
     * Otherwise the response bytes are decoded as UTF-8.
     */
    fun charStream(): Reader = reader ?: BomAwareReader(source(), charset()).also {
        reader = it
    }

    /**
     * Returns the response as a string.
     *
     * If the response starts with a
     * [Byte Order Mark (BOM)](https://en.wikipedia.org/wiki/Byte_order_mark), it is consumed and
     * used to determine the charset of the response bytes.
     *
     * Otherwise if the response has a `Content-Type` header that specifies a charset, that is used
     * to determine the charset of the response bytes.
     *
     * Otherwise the response bytes are decoded as UTF-8.
     *
     * 这个方法会把整个响应加载到内存中. 如果响应非常大的话，可能会触发[OutOfMemoryError].
     * 所以尽可能考虑使用`stream`。
     */
    @Throws(IOException::class)
    fun string(): String = source().use { source ->
        source.readString(charset = source.readBomAsCharset(charset()))
    }

    private fun charset() = contentType()?.charset(UTF_8) ?: UTF_8

    override fun close() = source().closeQuietly()

    internal class BomAwareReader(
            private val source: BufferedSource,
            private val charset: Charset
    ) : Reader() {

        private var closed: Boolean = false
        private var delegate: Reader? = null

        @Throws(IOException::class)
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (closed) throw IOException("Stream closed")

            val finalDelegate = delegate ?: InputStreamReader(
                    source.inputStream(),
                    source.readBomAsCharset(charset)).also {
                delegate = it
            }
            return finalDelegate.read(cbuf, off, len)
        }

        @Throws(IOException::class)
        override fun close() {
            closed = true
            delegate?.close() ?: run { source.close() }
        }
    }

    companion object {
        /**
         * Returns a new response body that transmits this string. If [contentType] is non-null and
         * lacks a charset, this will use UTF-8.
         */
        @JvmStatic
        @JvmName("create")
        fun String.toResponseBody(contentType: MediaType? = null): ResponseBody {
            var charset: Charset = UTF_8
            var finalContentType: MediaType? = contentType
            if (contentType != null) {
                val resolvedCharset = contentType.charset()
                if (resolvedCharset == null) {
                    charset = UTF_8
                    finalContentType = "$contentType; charset=utf-8".toMediaTypeOrNull()
                } else {
                    charset = resolvedCharset
                }
            }
            val buffer = Buffer().writeString(this, charset)
            return buffer.asResponseBody(finalContentType, buffer.size)
        }

        /** Returns a new response body that transmits this byte array. */
        @JvmStatic
        @JvmName("create")
        fun ByteArray.toResponseBody(contentType: MediaType? = null): ResponseBody {
            return Buffer()
                    .write(this)
                    .asResponseBody(contentType, size.toLong())
        }

        /** Returns a new response body that transmits this byte string. */
        @JvmStatic
        @JvmName("create")
        fun ByteString.toResponseBody(contentType: MediaType? = null): ResponseBody {
            return Buffer()
                    .write(this)
                    .asResponseBody(contentType, size.toLong())
        }

        /** Returns a new response body that transmits this source. */
        @JvmStatic
        @JvmName("create")
        fun BufferedSource.asResponseBody(
                contentType: MediaType? = null,
                contentLength: Long = -1L
        ): ResponseBody = object : ResponseBody() {
            override fun contentType() = contentType

            override fun contentLength() = contentLength

            override fun source() = this@asResponseBody
        }

//        @JvmStatic
//        @Deprecated(
//                message = "Moved to extension function. Put the 'content' argument first to fix Java",
//                replaceWith = ReplaceWith(
//                        expression = "content.toResponseBody(contentType)",
//                        imports = ["okhttp3.ResponseBody.Companion.toResponseBody"]
//                ),
//                level = DeprecationLevel.WARNING)
//        fun create(contentType: MediaType?, content: String) = content.toResponseBody(contentType)
//
//        @JvmStatic
//        @Deprecated(
//                message = "Moved to extension function. Put the 'content' argument first to fix Java",
//                replaceWith = ReplaceWith(
//                        expression = "content.toResponseBody(contentType)",
//                        imports = ["okhttp3.ResponseBody.Companion.toResponseBody"]
//                ),
//                level = DeprecationLevel.WARNING)
//        fun create(contentType: MediaType?, content: ByteArray) = content.toResponseBody(contentType)
//
//        @JvmStatic
//        @Deprecated(
//                message = "Moved to extension function. Put the 'content' argument first to fix Java",
//                replaceWith = ReplaceWith(
//                        expression = "content.toResponseBody(contentType)",
//                        imports = ["okhttp3.ResponseBody.Companion.toResponseBody"]
//                ),
//                level = DeprecationLevel.WARNING)
//        fun create(contentType: MediaType?, content: ByteString) = content.toResponseBody(contentType)
//
//        @JvmStatic
//        @Deprecated(
//                message = "Moved to extension function. Put the 'content' argument first to fix Java",
//                replaceWith = ReplaceWith(
//                        expression = "content.asResponseBody(contentType, contentLength)",
//                        imports = ["okhttp3.ResponseBody.Companion.asResponseBody"]
//                ),
//                level = DeprecationLevel.WARNING)
//        fun create(
//                contentType: MediaType?,
//                contentLength: Long,
//                content: BufferedSource
//        ) = content.asResponseBody(contentType, contentLength)
    }
}
