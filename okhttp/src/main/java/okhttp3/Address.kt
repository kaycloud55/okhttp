/*
 * Copyright (C) 2012 The Android Open Source Project
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

import java.net.Proxy
import java.net.ProxySelector
import java.util.Objects
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import okhttp3.internal.toImmutableList

/**
 *
 * 针对原始服务器的connection的描述。对于简单的connection来说，它就只包含server的hostname和port.如果配置了代理服务器的信息的话，它也会包含代理信息。
 * 在安全方面，它还包含了SSL socket factory和hostname verifier，certificate pinner。
 *
 * 共享[Address]的HTTP请求也会重新connection。也就是说，[Address]其实是跟[Connection]绑定的，一一对应的关系。
 * [Route]类似于[Address]的子类，由一个[Address]可以发展出多个[Route].
 *
 * 在[okhttp3.internal.connection.ExchangeFinder.findConnection]中，如果通过[Address]去连接池中找没有找到可用的连接的时候，会根据不同的Route继续去尝试。
 */
class Address(
        uriHost: String,
        uriPort: Int,
        /** Returns the service that will be used to resolve IP addresses for hostnames. */
        @get:JvmName("dns") val dns: Dns,

        /** Returns the socket factory for new connections. */
        @get:JvmName("socketFactory") val socketFactory: SocketFactory,

        /** Returns the SSL socket factory, or null if this is not an HTTPS address. */
        @get:JvmName("sslSocketFactory") val sslSocketFactory: SSLSocketFactory?,

        /** Returns the hostname verifier, or null if this is not an HTTPS address. */
        @get:JvmName("hostnameVerifier") val hostnameVerifier: HostnameVerifier?,

        /** Returns this address's certificate pinner, or null if this is not an HTTPS address. */
        @get:JvmName("certificatePinner") val certificatePinner: CertificatePinner?,

        /** Returns the client's proxy authenticator. */
        @get:JvmName("proxyAuthenticator") val proxyAuthenticator: Authenticator,

        /**
         * Returns this address's explicitly-specified HTTP proxy, or null to delegate to the {@linkplain
         * #proxySelector proxy selector}.
         */
        @get:JvmName("proxy") val proxy: Proxy?,

        protocols: List<Protocol>,
        connectionSpecs: List<ConnectionSpec>,

        /**
         * Returns this address's proxy selector. Only used if the proxy is null. If none of this
         * selector's proxies are reachable, a direct connection will be attempted.
         */
        @get:JvmName("proxySelector") val proxySelector: ProxySelector
) {
    /**
     * 只包含hostname和port，不包含查询参数那些东西，因为那些东西对于建立Route是不重要的。
     */
    @get:JvmName("url")
    val url: HttpUrl = HttpUrl.Builder()
            .scheme(if (sslSocketFactory != null) "https" else "http")
            .host(uriHost)
            .port(uriPort)
            .build()

    /**
     * 客户端支持的协议. 这个方法总会一个非空集合，它至少包含 [Protocol.HTTP_1_1].
     */
    @get:JvmName("protocols")
    val protocols: List<Protocol> = protocols.toImmutableList()

    @get:JvmName("connectionSpecs")
    val connectionSpecs: List<ConnectionSpec> =
            connectionSpecs.toImmutableList()

    override fun equals(other: Any?): Boolean {
        return other is Address &&
                url == other.url &&
                equalsNonHost(other)
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + url.hashCode()
        result = 31 * result + dns.hashCode()
        result = 31 * result + proxyAuthenticator.hashCode()
        result = 31 * result + protocols.hashCode()
        result = 31 * result + connectionSpecs.hashCode()
        result = 31 * result + proxySelector.hashCode()
        result = 31 * result + Objects.hashCode(proxy)
        result = 31 * result + Objects.hashCode(sslSocketFactory)
        result = 31 * result + Objects.hashCode(hostnameVerifier)
        result = 31 * result + Objects.hashCode(certificatePinner)
        return result
    }

    internal fun equalsNonHost(that: Address): Boolean {
        return this.dns == that.dns &&
                this.proxyAuthenticator == that.proxyAuthenticator &&
                this.protocols == that.protocols &&
                this.connectionSpecs == that.connectionSpecs &&
                this.proxySelector == that.proxySelector &&
                this.proxy == that.proxy &&
                this.sslSocketFactory == that.sslSocketFactory &&
                this.hostnameVerifier == that.hostnameVerifier &&
                this.certificatePinner == that.certificatePinner &&
                this.url.port == that.url.port
    }

    override fun toString(): String {
        return "Address{" +
                "${url.host}:${url.port}, " +
                (if (proxy != null) "proxy=$proxy" else "proxySelector=$proxySelector") +
                "}"
    }
}
