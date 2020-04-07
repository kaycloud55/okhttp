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

import java.io.IOException
import okio.Timeout

/**
 * Call就是一个已经准备好执行的请求。
 * Call接口封装了一对request/response（在HTTP/2中是1个流），它只能被执行一次。
 */
interface Call : Cloneable {
    /** 当前Call封装的Request. */
    fun request(): Request

    /**
     *
     * 同步调用，会阻塞当前线程直到有数据响应或者是有error。
     *
     * 为了避免资源泄露，调用方应该主动关闭[Response]，这同时会关闭底层的[ResponseBody]
     *
     * // 确保response被关闭
     * try (Response response = client.newCall(request).execute()) {
     *   ...
     * }
     * ```
     *
     * The caller may read the response body with the response's [Response.body] method. To avoid
     * leaking resources callers must [close the response body][ResponseBody] or the response.
     *
     * 注意：传输层成功（接受到HTTP响应码，headers和body）不代表应用层成功，因为可能返回的是404或者500这样的，实际上对应用层来说是请求失败的。
     *
     * @throws IOException 如果请求因为被取消、连接问题、超时等原因不能被成功执行，就会抛出这个异常。这表示的是服务端可能已经接受到了请求，但是这次请求客户端判定为失败了。
     * @throws IllegalStateException 重复执行一个已经被执行过的请求时
     */
    @Throws(IOException::class)
    fun execute(): Response

    /**
     * 在未来的某个时间点执行请求。
     *
     * 【dispatcher][OkHttpClient.dispatcher]决定了请求什么时候被执行。通常是立即执行，除非是当前有很多其他请求正在执行。
     *
     * 执行完成或者失败之后会回调responseCallback.
     *
     * @throws IllegalStateException when the call has already been executed.
     */
    fun enqueue(responseCallback: Callback)

    /** 取消当前请求。已经执行完毕的请求不能取消 */
    fun cancel()

    /**
     * Returns true if this call has been either [executed][execute] or [enqueued][enqueue]. It is an
     * error to execute a call more than once.
     */
    fun isExecuted(): Boolean

    fun isCanceled(): Boolean

    /**
     * 这里的超时是针对的整个过程：
     *  1.DNS解析
     *  2.建立连接
     *  3.写入请求体
     *  4.服务器处理
     *  5.读取响应
     *
     *  如果有重定向和重试的话，时间也包含在这个timeout之内。
     *
     * 相匹配的是这个参数：[OkHttpClient.Builder.callTimeout].
     */
    fun timeout(): Timeout

    /**
     * 重新创建一个请求，性能当然比new一个要好
     */
    public override fun clone(): Call

    interface Factory {
        fun newCall(request: Request): Call
    }
}
