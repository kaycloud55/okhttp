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
package okhttp3

import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 连接路径的抽象。
 * 表示一种路由策略。
 * 每个connection根据一个route来确定连接到服务器的路径。
 * 当客户端建立connection的时候，有多个选项：
 *
 *  * **HTTP proxy:* 一个host可能会对应多个代理服务器，通过[ProxySelector]进行选择。
 *  * **IP address:* 不管是通过代理还是直接连接服务器，建立socket连接都需要IP地址。在DNS解析的时候，
 *  DNS服务器可能会针对一个域名返回多个IP地址，在一个IP地址失败的时候，我们就可以尝试另外的。
 *
* 每个route都是针对上述这些选项的一个具体化。
 *
 * 从代码来看，一个route，就是一个address、proxy、socketAddress的组合
 */
class Route(
        @get:JvmName("address") val address: Address,
        /**
         * Returns the [Proxy] of this route.
         *
         * **Warning:** This may disagree with [Address.proxy] when it is null. When
         * the address's proxy is null, the proxy selector is used.
         */
        @get:JvmName("proxy") val proxy: Proxy,
        @get:JvmName("socketAddress") val socketAddress: InetSocketAddress
) {

    /**
     * Returns true if this route tunnels HTTPS through an HTTP proxy.
     * See [RFC 2817, Section 5.2][rfc_2817].
     *
     * [rfc_2817]: http://www.ietf.org/rfc/rfc2817.txt
     */
    fun requiresTunnel() = address.sslSocketFactory != null && proxy.type() == Proxy.Type.HTTP

    override fun equals(other: Any?): Boolean {
        return other is Route &&
                other.address == address &&
                other.proxy == proxy &&
                other.socketAddress == socketAddress
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + address.hashCode()
        result = 31 * result + proxy.hashCode()
        result = 31 * result + socketAddress.hashCode()
        return result
    }

    override fun toString(): String = "Route{$socketAddress}"
}
