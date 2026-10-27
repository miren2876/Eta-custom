package io.github.mangi.eta.core

import android.util.Log
import io.github.mangi.eta.BuildConfig

internal interface AgentLogger {
    /**
     * 记录仅用于开发期诊断的信息。
     *
     * supplier 只能构造诊断文本，不能承担程序正确性依赖的副作用；Release 构建会删除整次调用。
     */
    fun debug(message: () -> String)

    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}

internal object AndroidAgentLogger : AgentLogger {
    /** Release 包不输出任何日志：需要排查时用 debug 包。 */
    private val enabled = BuildConfig.DEBUG
    private val logThrottle = LogThrottle()

    override fun debug(message: () -> String) {
        if (!enabled) return
        Log.d(ModuleConfig.TAG, message())
    }

    override fun info(message: String) {
        if (!enabled) return
        Log.i(ModuleConfig.TAG, message)
    }

    override fun warn(message: String) {
        if (!enabled) return
        Log.w(ModuleConfig.TAG, message)
    }

    fun warnThrottled(
        key: String,
        windowMs: Long = ModuleConfig.HOT_PATH_LOG_WINDOW_MS,
        message: () -> String
    ) {
        if (!enabled) return
        if (logThrottle.shouldLog("warn:$key", windowMs)) {
            warn(message())
        }
    }

    override fun error(message: String, throwable: Throwable?) {
        if (!enabled) return
        if (throwable == null) {
            Log.e(ModuleConfig.TAG, message)
        } else {
            Log.e(ModuleConfig.TAG, message, throwable)
        }
    }

    fun errorThrottled(
        key: String,
        throwable: Throwable? = null,
        windowMs: Long = ModuleConfig.HOT_PATH_LOG_WINDOW_MS,
        message: () -> String
    ) {
        if (!enabled) return
        if (logThrottle.shouldLog("error:$key", windowMs)) {
            error(message(), throwable)
        }
    }
}
