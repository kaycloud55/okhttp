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
package okhttp3.internal.connection

import okhttp3.Route

/**
 *
 * 建立新的connection时要跳过的已经失败过的routes黑名单。
 *
 * 这属于OKHttp的优化策略，如果有一个新的route的host是包含在这个黑名单某个route的，那么就可以直接跳过。
 *
 */
class RouteDatabase {
    private val failedRoutes = mutableSetOf<Route>()

    /** Records a failure connecting to [failedRoute]. */
    @Synchronized
    fun failed(failedRoute: Route) {
        failedRoutes.add(failedRoute)
    }

    /** Records success connecting to [route]. */
    @Synchronized
    fun connected(route: Route) {
        failedRoutes.remove(route)
    }

    /** 某个route最近是否失败过，要跳过这个route */
    @Synchronized
    fun shouldPostpone(route: Route): Boolean = route in failedRoutes
}
