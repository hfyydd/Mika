package com.wangzan101.mika.superagent.bridge

import android.content.Context
import com.wangzan101.mika.superagent.SuperagentRuntimeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mika 本体的 Super 模式入口：开关本身在侧栏 Super 页；这里只保留运行桥。
 * 服务没 ready 时不把消息送进 Super 层。
 */
class MikaSuperRuntimeHost(context: Context) {
    private val app = context.applicationContext
    private val _superMode = MutableStateFlow(false)
    val superMode: StateFlow<Boolean> = _superMode.asStateFlow()

    fun setEnabled(value: Boolean) {
        _superMode.value = value
    }

    fun beginSuper(goal: String, conversationId: String): Boolean {
        if (!_superMode.value) return false
        return SuperagentBridge.get(app).send(conversationId, goal)
    }
}
