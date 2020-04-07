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

import java.util.ArrayDeque
import java.util.Collections
import java.util.Deque
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okhttp3.internal.assertThreadDoesntHoldLock
import okhttp3.internal.connection.RealCall
import okhttp3.internal.connection.RealCall.AsyncCall
import okhttp3.internal.okHttpName
import okhttp3.internal.threadFactory

/**
 * 异步请求执行的调度器。
 *
 * 每个dispatcher内部使用ExecutorService来处理任务（AsyncCall其实就是Runnable）。
 * 如果自定义executor，应该能并发执行[maxRequests]个请求。
 */
class Dispatcher constructor() {
    /**
     * 并发执行的最大请求数量. 超过这个数量的请求，会在内存中等待.
     *
     * If more than [maxRequests] requests are in flight when this is invoked, those requests will
     * remain in flight.
     */
    @get:Synchronized
    var maxRequests = 64
        set(maxRequests) {
            require(maxRequests >= 1) { "max < 1: $maxRequests" }
            synchronized(this) {
                field = maxRequests
            }
            promoteAndExecute() //设置maxRequest的同时会判断有没有请求可以执行
        }

    /**
     * The maximum number of requests for each host to execute concurrently.
     * 每个host并发的最大请求数量。
     * 这个限制的key是URL的host名.
     * 但是对于一个IP地址的request数量可能会超过这个限制：因为多个hostName可能共享同一个IP地址，
     * 或者是通过相同的HTTP代理路由。
     *
     * If more than [maxRequestsPerHost] requests are in flight when this is invoked, those requests
     * will remain in flight.
     *
     * WebSocket connections to hosts **do not** count against this limit.
     */
    @get:Synchronized
    var maxRequestsPerHost = 5
        set(maxRequestsPerHost) {
            require(maxRequestsPerHost >= 1) { "max < 1: $maxRequestsPerHost" }
            synchronized(this) {
                field = maxRequestsPerHost
            }
            promoteAndExecute()
        }

    /**
     * A callback to be invoked each time the dispatcher becomes idle (when the number of running
     * calls returns to zero).
     * 当dispatcher空闲(running calls数量为0)的时候就会回调。
     *
     * Note: The time at which a [call][Call] is considered idle is different depending on whether it
     * was run [asynchronously][Call.enqueue] or [synchronously][Call.execute]. Asynchronous calls
     * become idle after the [onResponse][Callback.onResponse] or [onFailure][Callback.onFailure]
     * callback has returned. Synchronous calls become idle once [execute()][Call.execute] returns.
     * This means that if you are doing synchronous calls the network layer will not truly be idle
     * until every returned [Response] has been closed.
     */
    @set:Synchronized
    @get:Synchronized
    var idleCallback: Runnable? = null

    private var executorServiceOrNull: ExecutorService? = null

    @get:Synchronized
    @get:JvmName("executorService")
    val executorService: ExecutorService
        get() {
            if (executorServiceOrNull == null) {
                executorServiceOrNull = ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS,
                    SynchronousQueue(), threadFactory("$okHttpName Dispatcher", false))
            }
            return executorServiceOrNull!!
        }

    /** Ready async calls in the order they'll be run. */
    private val readyAsyncCalls = ArrayDeque<AsyncCall>()

    /** Running asynchronous calls. Includes canceled calls that haven't finished yet. */
    private val runningAsyncCalls = ArrayDeque<AsyncCall>()

    /** Running synchronous calls. Includes canceled calls that haven't finished yet. */
    private val runningSyncCalls = ArrayDeque<RealCall>()

    constructor(executorService: ExecutorService) : this() {
        this.executorServiceOrNull = executorService
    }

    internal fun enqueue(call: AsyncCall) {
        synchronized(this) {
            readyAsyncCalls.add(call)

            // Mutate the AsyncCall so that it shares the AtomicInteger of an existing running call to
            // the same host.
            //这里需要共享一个callsPerHost参数，用来限制一个主机的并发请求数量
            if (!call.call.forWebSocket) {
                val existingCall = findExistingCallWithHost(call.host)
                if (existingCall != null) call.reuseCallsPerHostFrom(existingCall)
            }
        }
        promoteAndExecute()
    }

    private fun findExistingCallWithHost(host: String): AsyncCall? {
        for (existingCall in runningAsyncCalls) {
            if (existingCall.host == host) return existingCall
        }
        for (existingCall in readyAsyncCalls) {
            if (existingCall.host == host) return existingCall
        }
        return null
    }

    /**
     * Cancel all calls currently enqueued or executing. Includes calls executed both
     * [synchronously][Call.execute] and [asynchronously][Call.enqueue].
     */
    @Synchronized
    fun cancelAll() {
        for (call in readyAsyncCalls) {
            call.call.cancel()
        }
        for (call in runningAsyncCalls) {
            call.call.cancel()
        }
        for (call in runningSyncCalls) {
            call.cancel()
        }
    }

    /**
     * Promotes eligible calls from [readyAsyncCalls] to [runningAsyncCalls] and runs them on the
     * executor service. Must not be called with synchronization because executing calls can call
     * into user code.
     * 从[readyAsyncCalls]中选出符合条件的，然后把它添加到[runningAsyncCalls]，并且在executor service上执行。
     * 这里不能同步调用，因为执行请求可能会走入到用户的代码中。
     *
     * @return true if the dispatcher is currently running calls.
     */
    private fun promoteAndExecute(): Boolean {
        this.assertThreadDoesntHoldLock()

        val executableCalls = mutableListOf<AsyncCall>()
        val isRunning: Boolean
        synchronized(this) {
            val i = readyAsyncCalls.iterator()
            while (i.hasNext()) {
                val asyncCall = i.next()

                if (runningAsyncCalls.size >= this.maxRequests) break // 超过最大并发请求数量
                if (asyncCall.callsPerHost.get() >= this.maxRequestsPerHost) continue // 超过host最大请求数量

                i.remove()
                asyncCall.callsPerHost.incrementAndGet()
                executableCalls.add(asyncCall)
                runningAsyncCalls.add(asyncCall)
            }
            isRunning = runningCallsCount() > 0
        }

        for (i in 0 until executableCalls.size) {
            val asyncCall = executableCalls[i]
            asyncCall.executeOn(executorService)
        }

        return isRunning
    }

    /** Used by `Call#execute` to signal it is in-flight.
     * `Call#execute`调用过程中通知
     */
    @Synchronized
    internal fun executed(call: RealCall) {
        runningSyncCalls.add(call)
    }

    /**
     * 'AsyncCall#run`完成后回调
     */
    internal fun finished(call: AsyncCall) {
        call.callsPerHost.decrementAndGet()
        finished(runningAsyncCalls, call)
    }

    /**
     * `Call#execute()`完成后回调
     */
    internal fun finished(call: RealCall) {
        finished(runningSyncCalls, call)
    }

    /**
     * 一次请求调用结束后会调用finish()
     * 不管是同步请求还是异步请求，结束后都会走到这里
     */
    private fun <T> finished(calls: Deque<T>, call: T) {
        val idleCallback: Runnable?
        synchronized(this) {
            //从队列中移除，移除失败抛出异常
            if (!calls.remove(call)) throw AssertionError("Call wasn't in-flight!")
            idleCallback = this.idleCallback
        }

        val isRunning = promoteAndExecute() //这里会从ready队列中选中符合条件的call，开始执行
        //每个请求完成之后都会进行进行这个检查，用来判断链接是否空闲
        if (!isRunning && idleCallback != null) {
            idleCallback.run()
        }
    }

    /** 返回当前正在等待执行的call的快照 */
    @Synchronized
    fun queuedCalls(): List<Call> {
        return Collections.unmodifiableList(readyAsyncCalls.map { it.call })
    }

    /** 返回正在执行的calls的快照 */
    @Synchronized
    fun runningCalls(): List<Call> {
        return Collections.unmodifiableList(runningSyncCalls + runningAsyncCalls.map { it.call })
    }

    @Synchronized
    fun queuedCallsCount(): Int = readyAsyncCalls.size

    @Synchronized
    fun runningCallsCount(): Int = runningAsyncCalls.size + runningSyncCalls.size

//    @JvmName("-deprecated_executorService")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "executorService"),
//        level = DeprecationLevel.ERROR)
//    fun executorService(): ExecutorService = executorService
}
