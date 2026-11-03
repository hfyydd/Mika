package com.wangzan101.mika.superagent.bridge

import android.content.Intent

/** 服务可用性钩子；开关、入口和界面都用它判断本地 Super 是否真的 ready。 */
interface SuperagentRuntimeHost {
    fun onStarted(): Unit
    fun onStopped(): Unit
}

object SuperagentRuntimeHosts {
    private val listeners = mutableListOf<SuperagentRuntimeHost>()
    fun register(listener: SuperagentRuntimeHost) {
        listeners += listener
    }
    fun unregister(listener: SuperagentRuntimeHost) {
        listeners -= listener
    }
    fun emitStarted() {
        listeners.toList().forEach { it.onStarted() }
    }
    fun emitStopped() {
        listeners.toList().forEach { it.onStopped() }
    }
}
