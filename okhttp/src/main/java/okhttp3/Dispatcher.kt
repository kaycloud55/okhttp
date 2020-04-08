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
 * 如果开发者需要自定义executor，要注意定义的线程池应该能并发执行[maxRequests]个请求，也就是MaximumThreadPool应该至少是maxRequests.
 */
class Dispatcher constructor() {
    /**
     * 并发执行的最大请求数量. 超过这个数量的请求，会在内存中等待.
     *
     * 如果调用时正在处理[maxRequests]个以上的请求,会继续处理完。
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
     * 每个host并发的最大请求数量。
     * 这个限制的key是URL的host名.
     * 但是对于一个IP地址的request数量可能会超过这个限制：因为多个hostName可能共享同一个IP地址，
     * 或者是通过相同的HTTP代理路由。
     *
     * 如果调用时正在处理[maxRequests]个以上的请求,会继续处理完。
     *
     * webSocket连接不受这个限制。
     */
    @get:Synchronized
    var maxRequestsPerHost = 5
        set(maxRequestsPerHost) {
            require(maxRequestsPerHost >= 1) { "max < 1: $maxRequestsPerHost" }
            synchronized(this) {
                field = maxRequestsPerHost
            }
            promoteAndExecute() //优化任务队列并且执行任务。
        }

    /**
     * 当dispatcher空闲(running calls数量为0)的时候就会回调，也就是[runningAsyncCalls]和[runningSyncCalls]都为空的时候。
     *
     * Note: The time at which a [call][Call] is considered idle is different depending on whether it
     * was run [asynchronously][Call.enqueue] or [synchronously][Call.execute].
     *
     * 注意：判断某个时刻[Call]是否处于idle状态，和他们是否在执行[Call.enqueue]或者是[Call.execute]方法是不一样的。
     *  对于异步请求而言，在[onResponse][Callback.onResponse]或者是[onFailure][Callback.onFailure]return之后，它就变成idle的了。
     *  对于同步请求而言，在[execute()][Call.execute] returns之后，它变成idle的。
     *
     * 这意味着，对于同步请求来说，只有当Response被关闭之后，网络层才会真正变的空闲。
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
                //其实就是一个CachedThreadPool。
                executorServiceOrNull = ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS,
                        SynchronousQueue(), threadFactory("$okHttpName Dispatcher", false))
            }
            return executorServiceOrNull!!
        }

    /** 等待执行的异步请求队列.
     *  为什么要使用双端队列？很简单因为网络请求执行顺序跟排队一样，讲究先来后到，新来的请求放队尾，执行请求从对头部取。
     *  说到这LinkedList表示不服，我们知道LinkedList同样也实现了Deque接口，内部是用链表实现的双端队列，那为什么不用LinkedList呢？
     *  实际上这与readyAsyncCalls向runningAsyncCalls转换有关，当执行完一个请求或调用enqueue方法入队新的请求时，
     *  会对readyAsyncCalls进行一次遍历，将那些符合条件的等待请求转移到runningAsyncCalls队列中并交给线程池执行。
     *  尽管二者都能完成这项任务，但是由于链表的数据结构致使元素离散的分布在内存的各个位置，CPU缓存无法带来太多的便利，
     *  另外在垃圾回收时，使用数组结构的效率要优于链表。
     */
    private val readyAsyncCalls = ArrayDeque<AsyncCall>()

    /** 正在执行的异步请求队列. 包含了已经调用了cancel方法，但是还没有真正完成的call. */
    private val runningAsyncCalls = ArrayDeque<AsyncCall>()

    /** 正在执行的同步队列. 包含了已经调用了cancel方法，但是还没有真正完成的call. */
    private val runningSyncCalls = ArrayDeque<RealCall>()

    constructor(executorService: ExecutorService) : this() {
        this.executorServiceOrNull = executorService
    }

    internal fun enqueue(call: AsyncCall) {
        synchronized(this) {
            readyAsyncCalls.add(call)

            //这里需要共享一个callsPerHost参数，用来限制一个主机的并发请求数量。这个callsPerHost是个原子类。
            if (!call.call.forWebSocket) {
                //这两行代码的作用在于把已有的callPerHost字段拿过来赋值到新的Call上
                val existingCall = findExistingCallWithHost(call.host)
                if (existingCall != null) call.reuseCallsPerHostFrom(existingCall)
            }
        }
        promoteAndExecute()
    }

    /**
     * 针对每个host，都有并发连接数量的限制。
     * 这里的目的在于复用Call，如果针对当前Host已经有了建立了Call，就会返回已经存在的Call。
     */
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
     * 取消当前所有已经执行完和正在执行的. 包括同步和异步的.
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
     * 从[readyAsyncCalls]中选出符合条件的，然后把它添加到[runningAsyncCalls]，并且放到线程池中执行。
     * 这里必须同步调用，因为执行请求可能会走入到用户的代码中。
     *
     * @return true if the dispatcher is currently running calls.
     */
    private fun promoteAndExecute(): Boolean {
        this.assertThreadDoesntHoldLock()

        val executableCalls = mutableListOf<AsyncCall>()
        val isRunning: Boolean
        //锁的是当前Dispatcher
        synchronized(this) {
            val i = readyAsyncCalls.iterator()
            while (i.hasNext()) {
                val asyncCall = i.next()
                //注意这里一个是break，一个是continue。区别在于，如果host的call达到上限，可以继续判断，因为下一个call可能是不同的host的。
                if (runningAsyncCalls.size >= this.maxRequests) break // 已经超过最大并发请求数量，不能继续执行
                if (asyncCall.callsPerHost.get() >= this.maxRequestsPerHost) continue // 已经超过host最大请求数量，不能继续执行
                //从等待队列中移除
                i.remove()
                asyncCall.callsPerHost.incrementAndGet() //这个应该是个volatile的变量
                //放到执行队列中去
                executableCalls.add(asyncCall)
                //批量放入
                runningAsyncCalls.add(asyncCall)
            }
            isRunning = runningCallsCount() > 0
        }
        //针对放入的这一批call，调用执行方法。
        for (i in 0 until executableCalls.size) {
            val asyncCall = executableCalls[i]
            asyncCall.executeOn(executorService)
        }

        return isRunning
    }

    /** U
     * `Call#execute`会调用这个方法，把同步请求放入执行队列中。
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
        return Collections.unmodifiableList(readyAsyncCalls.map { it.call }) //返回的是一个不可变的list
    }

    /** 返回正在执行的calls的快照 */
    @Synchronized
    fun runningCalls(): List<Call> {
        return Collections.unmodifiableList(runningSyncCalls + runningAsyncCalls.map { it.call })
    }

    @Synchronized
    fun queuedCallsCount(): Int = readyAsyncCalls.size //正在排队的call数量

    @Synchronized
    fun runningCallsCount(): Int = runningAsyncCalls.size + runningSyncCalls.size //正在执行的call的数量

//    @JvmName("-deprecated_executorService")
//    @Deprecated(
//        message = "moved to val",
//        replaceWith = ReplaceWith(expression = "executorService"),
//        level = DeprecationLevel.ERROR)
//    fun executorService(): ExecutorService = executorService
}
