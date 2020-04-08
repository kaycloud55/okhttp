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

import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import kotlin.text.Charsets.UTF_8
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.internal.checkOffsetAndCount
import okio.BufferedSink
import okio.ByteString
import okio.source

abstract class RequestBody {

    /** Returns the Content-Type header for this body. */
    abstract fun contentType(): MediaType?

    /**
     * Returns the number of bytes that will be written to sink in a call to [writeTo],
     * or -1 if that count is unknown.
     */
    @Throws(IOException::class)
    open fun contentLength(): Long = -1L

    /** Writes the content of this request to [sink]. */
    @Throws(IOException::class)
    abstract fun writeTo(sink: BufferedSink)

    /**
     * 双工请求体在网络上以及 OkHttp 和应用程序之间的 API 协议中的传输方式是特殊的。
     *
     * 除非被子类覆盖，否则此方法返回 false。
     *
     * ### 双工传输
     *
     * 对于常规的 HTTP 调用，请求总是在响应开始接收之前完成发送。 双工的请求和响应可以交错！ 也就是说，请求主体字节可以在接收到响应头或主体字节之后发送。
     *
     * 虽然任何呼叫都可以以双工呼叫的形式启动，但只有专门为这种非标准交互设计的 web 服务器才会使用它。 截至2019-01年，该模式唯一广泛使用的实现是 gRPC。
     *
     * 由于交织数据的编码对于 http / 1没有很好的定义，双工请求体只能与 http / 2一起使用。 在传输 HTTP 请求之前，对 HTTP / 1服务器的调用将失败。 如果您不能确保您的客户机和服务器都支持 http / 2，那么不要使用这个特性。
     *
     * ### Duplex APIs
     *
     * 对于常规的请求函数体，向传递给 RequestBody.writeTo 的接收器写入字节是不合法的。 对于双工请求主体，该条件被解除。 这样的写操作发生在应用程序提供的线程上，并且可能与响应主体的读操作同时发生。 对于双工请求主体，writeTo 应该快速返回，可能是将提供的请求主体交给另一个线程执行写操作。
     *
     * [grpc]: https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md
     */
    open fun isDuplex(): Boolean = false

    /**
     * 如果此机构期望最多调用 writeTo 一次，并且最多可以传输一次，则返回 true。 当写入请求主体具有破坏性且在发送请求主体之后不可能重新创建请求主体时，通常使用这种方法。
     *
     * 除非被子类覆盖，否则此方法返回 false。
     *
     * 默认情况下，当原始请求由于以下任何原因而失败时，OkHttp 会尝试重新传输请求主体:
     *
     *  * 一个陈旧的连接。 请求是在一个重用的连接上发出的，该重用的连接已经被服务器关闭
     *  * A client timeout (HTTP 408).
     *  * A authorization challenge (HTTP 401 and 407) that is satisfied by the [Authenticator].
     *  * A retryable server failure (HTTP 503 with a `Retry-After: 0` response header).
     *  * A misdirected request (HTTP 421) on a coalesced connection.
     */
    open fun isOneShot(): Boolean = false

    companion object {

        /**
         * Returns a new request body that transmits this string. If [contentType] is non-null and lacks
         * a charset, this will use UTF-8.
         */
        @JvmStatic
        @JvmName("create")
        fun String.toRequestBody(contentType: MediaType? = null): RequestBody {
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
            val bytes = toByteArray(charset)
            return bytes.toRequestBody(finalContentType, 0, bytes.size)
        }

        /** Returns a new request body that transmits this. */
        @JvmStatic
        @JvmName("create")
        fun ByteString.toRequestBody(contentType: MediaType? = null): RequestBody {
            return object : RequestBody() {
                override fun contentType() = contentType

                override fun contentLength() = size.toLong()

                override fun writeTo(sink: BufferedSink) {
                    sink.write(this@toRequestBody)
                }
            }
        }

        /** Returns a new request body that transmits this. */
        @JvmOverloads
        @JvmStatic
        @JvmName("create")
        fun ByteArray.toRequestBody(
                contentType: MediaType? = null,
                offset: Int = 0,
                byteCount: Int = size
        ): RequestBody {
            checkOffsetAndCount(size.toLong(), offset.toLong(), byteCount.toLong())
            return object : RequestBody() {
                override fun contentType() = contentType

                override fun contentLength() = byteCount.toLong()

                override fun writeTo(sink: BufferedSink) {
                    sink.write(this@toRequestBody, offset, byteCount)
                }
            }
        }

        /** Returns a new request body that transmits the content of this. */
        @JvmStatic
        @JvmName("create")
        fun File.asRequestBody(contentType: MediaType? = null): RequestBody {
            return object : RequestBody() {
                override fun contentType() = contentType

                override fun contentLength() = length()

                override fun writeTo(sink: BufferedSink) {
                    source().use { source -> sink.writeAll(source) }
                }
            }
        }

        @JvmStatic
        @Deprecated(
                message = "Moved to extension function. Put the 'content' argument first to fix Java",
                replaceWith = ReplaceWith(
                        expression = "content.toRequestBody(contentType)",
                        imports = ["okhttp3.RequestBody.Companion.toRequestBody"]
                ),
                level = DeprecationLevel.WARNING)
        fun create(contentType: MediaType?, content: String) = content.toRequestBody(contentType)

        @JvmStatic
        @Deprecated(
                message = "Moved to extension function. Put the 'content' argument first to fix Java",
                replaceWith = ReplaceWith(
                        expression = "content.toRequestBody(contentType)",
                        imports = ["okhttp3.RequestBody.Companion.toRequestBody"]
                ),
                level = DeprecationLevel.WARNING)
        fun create(
                contentType: MediaType?,
                content: ByteString
        ): RequestBody = content.toRequestBody(contentType)

        @JvmOverloads
        @JvmStatic
        @Deprecated(
                message = "Moved to extension function. Put the 'content' argument first to fix Java",
                replaceWith = ReplaceWith(
                        expression = "content.toRequestBody(contentType, offset, byteCount)",
                        imports = ["okhttp3.RequestBody.Companion.toRequestBody"]
                ),
                level = DeprecationLevel.WARNING)
        fun create(
                contentType: MediaType?,
                content: ByteArray,
                offset: Int = 0,
                byteCount: Int = content.size
        ) = content.toRequestBody(contentType, offset, byteCount)

        @JvmStatic
        @Deprecated(
                message = "Moved to extension function. Put the 'file' argument first to fix Java",
                replaceWith = ReplaceWith(
                        expression = "file.asRequestBody(contentType)",
                        imports = ["okhttp3.RequestBody.Companion.asRequestBody"]
                ),
                level = DeprecationLevel.WARNING)
        fun create(contentType: MediaType?, file: File) = file.asRequestBody(contentType)
    }
}
