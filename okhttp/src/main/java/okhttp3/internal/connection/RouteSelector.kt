/*
 * Copyright (C) 2012 Square, Inc.
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
package okhttp3.internal.connection

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketException
import java.net.UnknownHostException
import java.util.NoSuchElementException
import okhttp3.Address
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Route
import okhttp3.internal.immutableListOf
import okhttp3.internal.toImmutableList

/**
 * 选择连接到origin server的route。
 *
 * 每个connection都需要proxy server、IP address和TLS版本。
 * 每个route其实就是在Address的基础上，进行排列组合——因为Address声明的是host，它可能对应多个IP地址；proxy也可能有多个。
 *
 */
class RouteSelector(
        private val address: Address,
        private val routeDatabase: RouteDatabase,
        private val call: Call,
        private val eventListener: EventListener
) {
    /* State for negotiating the next proxy to use. */
    private var proxies = emptyList<Proxy>()
    private var nextProxyIndex: Int = 0

    /* State for negotiating the next socket address to use. */
    private var inetSocketAddresses = emptyList<InetSocketAddress>()

    /* State for negotiating failed routes */
    private val postponedRoutes = mutableListOf<Route>()

    init {
        resetNextProxy(address.url, address.proxy)
    }

    /**
     * Returns true if there's another set of routes to attempt. Every address has at least one route.
     */
    operator fun hasNext(): Boolean = hasNextProxy() || postponedRoutes.isNotEmpty()

    @Throws(IOException::class)
    operator fun next(): Selection {
        if (!hasNext()) throw NoSuchElementException()

        // 计算接下来要尝试的routes
        val routes = mutableListOf<Route>()
        while (hasNextProxy()) {
            // 最近失败过的route总是放到最后才尝试
            // 例如, 现在有两个代理，所有和proxy1关联的route都会放到后面再尝试, 先尝试proxy2对应的route.
            // 只有当前的route都尝试完了，才会去尝试那些在routeDataBase中的
            val proxy = nextProxy()
            for (inetSocketAddress in inetSocketAddresses) {
                val route = Route(address, proxy, inetSocketAddress)
                if (routeDatabase.shouldPostpone(route)) {
                    postponedRoutes += route //记录跳过的route
                } else {
                    routes += route
                }
            }

            if (routes.isNotEmpty()) {
                break
            }
        }

        if (routes.isEmpty()) {
            // 已经尝试了OK的route，都不行，所以要尝试postponed的那部分了。
            routes += postponedRoutes
            postponedRoutes.clear()
        }

        return Selection(routes)
    }

    /** Prepares the proxy servers to try. */
    private fun resetNextProxy(url: HttpUrl, proxy: Proxy?) {
        fun selectProxies(): List<Proxy> {
            // If the user specifies a proxy, try that and only that.
            if (proxy != null) return listOf(proxy)

            // If the URI lacks a host (as in "http://</"), don't call the ProxySelector.
            val uri = url.toUri()
            if (uri.host == null) return immutableListOf(Proxy.NO_PROXY)

            // Try each of the ProxySelector choices until one connection succeeds.
            val proxiesOrNull = address.proxySelector.select(uri)
            if (proxiesOrNull.isNullOrEmpty()) return immutableListOf(Proxy.NO_PROXY)

            return proxiesOrNull.toImmutableList()
        }

        eventListener.proxySelectStart(call, url)
        proxies = selectProxies()
        nextProxyIndex = 0
        eventListener.proxySelectEnd(call, url, proxies)
    }

    /** 是否还有下一个代理可以尝试. */
    private fun hasNextProxy(): Boolean = nextProxyIndex < proxies.size

    /** Returns the next proxy to try. May be PROXY.NO_PROXY but never null. */
    @Throws(IOException::class)
    private fun nextProxy(): Proxy {
        if (!hasNextProxy()) {
            throw SocketException(
                    "No route to ${address.url.host}; exhausted proxy configurations: $proxies")
        }
        val result = proxies[nextProxyIndex++]
        resetNextInetSocketAddress(result)
        return result
    }

    /** Prepares the socket addresses to attempt for the current proxy or host. */
    @Throws(IOException::class)
    private fun resetNextInetSocketAddress(proxy: Proxy) {
        // Clear the addresses. Necessary if getAllByName() below throws!
        val mutableInetSocketAddresses = mutableListOf<InetSocketAddress>()
        inetSocketAddresses = mutableInetSocketAddresses

        val socketHost: String
        val socketPort: Int
        if (proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.SOCKS) {
            socketHost = address.url.host
            socketPort = address.url.port
        } else {
            val proxyAddress = proxy.address()
            require(proxyAddress is InetSocketAddress) {
                "Proxy.address() is not an InetSocketAddress: ${proxyAddress.javaClass}"
            }
            socketHost = proxyAddress.socketHost
            socketPort = proxyAddress.port
        }

        if (socketPort !in 1..65535) {
            throw SocketException("No route to $socketHost:$socketPort; port is out of range")
        }

        if (proxy.type() == Proxy.Type.SOCKS) {
            mutableInetSocketAddresses += InetSocketAddress.createUnresolved(socketHost, socketPort)
        } else {
            eventListener.dnsStart(call, socketHost)

            // Try each address for best behavior in mixed IPv4/IPv6 environments.
            val addresses = address.dns.lookup(socketHost)
            if (addresses.isEmpty()) {
                throw UnknownHostException("${address.dns} returned no addresses for $socketHost")
            }

            eventListener.dnsEnd(call, socketHost, addresses)

            for (inetAddress in addresses) {
                mutableInetSocketAddresses += InetSocketAddress(inetAddress, socketPort)
            }
        }
    }

    /** selection就是对一系列可选择的路由的封装. */
    class Selection(val routes: List<Route>) {
        private var nextRouteIndex = 0

        operator fun hasNext(): Boolean = nextRouteIndex < routes.size

        operator fun next(): Route {
            if (!hasNext()) throw NoSuchElementException()
            return routes[nextRouteIndex++]
        }
    }

    companion object {
        /** Obtain a host string containing either an actual host name or a numeric IP address. */
        val InetSocketAddress.socketHost: String
            get() {
                // The InetSocketAddress was specified with a string (either a numeric IP or a host name). If
                // it is a name, all IPs for that name should be tried. If it is an IP address, only that IP
                // address should be tried.
                val address = address ?: return hostName

                // The InetSocketAddress has a specific address: we should only try that address. Therefore we
                // return the address and ignore any host name that may be available.
                return address.hostAddress
            }
    }
}
