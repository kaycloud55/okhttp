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

interface Callback {
    /**
     * 当由于取消、连接性问题或超时而无法执行请求时调用。 因为网络在交换期间可能会失败，所以远程服务器可能在失败之前接受请求。
     */
    fun onFailure(call: Call, e: IOException)

    /**
     * 当远程服务器成功返回 HTTP 响应时调用。
     * 可以在回调里面继续读取带有 [Response.body] 的响应体。
     * 响应仍然是active的，直到它的响应主体被关闭。 回调的接收方可能使用另一个线程上的响应体。
     *
     * 请注意，传输层成功(接收 HTTP 响应代码、头和正文)并不一定表示应用层成功: 响应可能仍然表示不满意的 HTTP 响应代码，如404或500。
     */
    @Throws(IOException::class)
    fun onResponse(call: Call, response: Response)
}
