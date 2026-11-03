package com.wangzan101.mika.superagent.bridge

import android.content.Context
import com.wangzan101.mika.superagent.SuperagentRuntimeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Mika 本体的 Super 模式入口：开关本身在侧栏 Super 页；这里只保留运行桥。
 * 服务 ready 还需要运行服务置 ready；这里只看用户开关。
 */
class MikaSuperRuntimeHost(context: Context) {
    private val app = context.applicationContext
    private val _userOn = MutableStateFlow(false)
    private val bridge = SuperagentBridge.get(app)

    init {
        SuperagentRuntimeHosts.register(object : SuperagentRuntimeHost {
            override fun onStarted() { _userOn.value = true }
            override fun onStopped() { _userOn.value = false }
        })
    }

    /** 用户开关与服务 ready 同时成立才算 active。 */
    val superMode: StateFlow<Boolean> = combine(_userOn, bridge.enabled) { userOn, ready -> userOn && ready }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, false)

    fun setEnabled(value: Boolean) {
        _userOn.value = value
    }

    val statuses: StateFlow<Map<String, SuperWorkStatus>> get() = bridge.statuses

    fun beginSuper(goal: String, conversationId: String): Boolean {
        if (!superMode.value) return false
        return SuperagentBridge.get(app).send(conversationId, goal)
    }
}
