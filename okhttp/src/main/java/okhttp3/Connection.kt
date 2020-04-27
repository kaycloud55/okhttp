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
package okhttp3

import java.net.Socket

/**
 * HTTP、HTTPS或者是HTTPS+HTTP/2中socket和stream的抽象。对应于HTTP的连接，HTTP/2的流。
 *
 * Connection是同时支持多个exchange的，也就是同时承载多个请求（类似HTTP/2的多路复用的概念）。
 *
 * Connection可能是直接指向原始服务器的，也可能是指向代理的。
 *
 * 连接池中管理的就是这个类的实例。
 *
 * Do not confuse this class with the misnamed `HttpURLConnection`, which isn't so much a connection
 * as a single request/response exchange.
 *
 * ## Modern TLS
 *
 * There are trade-offs when selecting which options to include when negotiating a secure connection
 * to a remote host. Newer TLS options are quite useful:
 *
 *  * Server Name Indication (SNI) enables one IP address to negotiate secure connections for
 *    multiple domain names.
 *
 *  * Application Layer Protocol Negotiation (ALPN) enables the HTTPS port (443) to be used to
 *    negotiate HTTP/2.
 *
 * Unfortunately, older HTTPS servers refuse to connect when such options are presented. Rather than
 * avoiding these options entirely, this class allows a connection to be attempted with modern
 * options and then retried without them should the attempt fail.
 *
 * ## Connection Reuse
 *
 * ## 连接复用
 *
 * 每个连接都可以承载大量的stream，具体的数量取决于底层的协议版本。对于HTTP/1.x，每个连接对应0个或1个stream，对于HTTP/2，
 * 每个连接可以承载大量的流，这取决于`SETTINGS_MAX_CONCURRENT_STREAMS`的配置。（HTTP/2在流的基础上执行请求的多路复用，而Connection是执行流的多路复用）
 * 没有承载流的connection视作空闲的，可以被复用。
 * 重用一个连接的成本比新建低很多，所以链接一般会被keep alive.
 *
 * When a single logical call requires multiple streams due to redirects or authorization
 * challenges, we prefer to use the same physical connection for all streams in the sequence. There
 * are potential performance and behavior consequences to this preference. To support this feature,
 * this class separates _allocations_ from _streams_. An allocation is created by a call, used for
 * one or more streams, and then released. An allocated connection won't be stolen by other calls
 * while a redirect or authorization challenge is being handled.
 *
 * When the maximum concurrent streams limit is reduced, some allocations will be rescinded.
 * Attempting to create new streams on these allocations will fail.
 *
 * Note that an allocation may be released before its stream is completed. This is intended to make
 * bookkeeping easier for the caller: releasing the allocation as soon as the terminal stream（控制帧） has
 * been found. But only complete the stream once its data stream has been exhausted.
 */
interface Connection {
    /** Returns the route used by this connection. */
    fun route(): Route

    /**
     * 当前链接使用的socket.
     * 如果是HTTPS链接，返回[SSL socket][javax.net.ssl.SSLSocket] .
     * 如果是HTTP/2连接，这个socket将会被多个并发的call公用。
     */
    fun socket(): Socket

    /**
     * TLS握手快照，如果不是HTTPS连接的话返回null。
     */
    fun handshake(): Handshake?

    /**
     * 当前链接协商出来的协议, 默认是[Protocol.HTTP_1_1] .
     * 即使服务端使用的是 [Protocol.HTTP_1_0] ，这个方法也会返回[Protocol.HTTP_1_0].
     */
    fun protocol(): Protocol
}
