package com.wangzan101.mika.superagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import android.os.IBinder
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.superagent.EffectQueue
import com.wangzan101.mika.core.superagent.Engine
import com.wangzan101.mika.core.superagent.FileEffectStore
import com.wangzan101.mika.core.superagent.FilePolicyStore
import com.wangzan101.mika.core.superagent.FileSuperagentSettingsStore
import com.wangzan101.mika.core.superagent.FileSuperagentStore
import com.wangzan101.mika.core.superagent.DEFAULT_POLICY_VERSION
import com.wangzan101.mika.core.superagent.LlmRouter
import com.wangzan101.mika.core.superagent.Runner
import com.wangzan101.mika.core.superagent.SourceOutbox
import com.wangzan101.mika.core.superagent.SuperagentRuntime
import com.wangzan101.mika.core.superagent.SuperagentSettings
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.superagent.bridge.SuperagentBridge
import com.wangzan101.mika.superagent.bridge.SuperagentLocalReplyChannel
import com.wangzan101.mika.superagent.bridge.SuperagentLocalSource
import com.wangzan101.mika.superagent.bridge.SuperagentRuntimeHosts
import com.wangzan101.mika.superagent.bridge.SuperagentStatusObserver
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Super 层的宿主服务:只装配 core 的 Runtime 与本地聊天桥;不接管 TaskService,也不改既有任务链。
 */
class SuperagentRuntimeService : Service() {
    private val bridge by lazy { SuperagentBridge.get(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val localSource = SuperagentLocalSource()
    private val statusObserver = SuperagentStatusObserver(this)
    private var runtime: SuperagentRuntime? = null
    private var policyStore: FilePolicyStore? = null
    private var watchedPolicy: com.wangzan101.mika.core.superagent.Policy? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW),
        )
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = FileSuperagentSettingsStore(settingsFile()).load()
        if (!settings.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startRuntime(settings)
            bridge.setEnabled(true)
            bridge.registerSource(localSource)
            SuperagentRuntimeHosts.emitStarted()
            return START_STICKY
        } catch (e: IllegalStateException) {
            stopSelf()
            return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        runtime?.stop()
        runtime = null
        scope.cancel()
        SuperagentRuntimeHosts.emitStopped()
        bridge.unregisterSource()
        bridge.setEnabled(false)
        super.onDestroy()
    }

    private fun startRuntime(settings: SuperagentSettings) {
        if (runtime != null) return
        val root = dataRoot()
        val policyStore = FilePolicyStore(root.resolve("policy.json").toPath()).also { this.policyStore = it }
        val initialPolicy = bootstrapPolicy(policyStore)
        val model = ProviderStore.get(this).modelFor(ModelRole.Light, ModelSession.of("superagent-local"))
            ?: ProviderStore.get(this).modelFor(ModelRole.Main, ModelSession.of("superagent-local"))
            ?: throw IllegalStateException("super-agent router model unavailable")

        val engine = Engine(
            store = FileSuperagentStore(root.resolve("state.json").toPath()),
            router = LlmRouter(model),
            executor = null,
        )
        val effects = EffectQueue(FileEffectStore(root.resolve("effects.json").toPath()))
        val localOutbox = SourceOutbox(
            effects,
            mapOf("chat:${SuperagentLocalSource.SOURCE_INSTANCE}" to SuperagentLocalReplyChannel(this)),
        )
        val runner = Runner(
            engine,
            TicketHostTaskExecutor(this) { engine.snapshot() },
            effects,
            localOutbox,
        )
        val triggerArchive = FileTriggerArchive(this)
        runtime = SuperagentRuntime(
            engine = engine,
            runner = runner,
            policy = { policyStore.load() },
            sources = listOf(localSource),
            effects = effects,
            outbox = localOutbox,
            onEvalOutcomes = ::reportOutcomes,
            onTriggerStored = { trigger -> triggerArchive.append(trigger) },
        ).also { runtime ->
            watchedPolicy = initialPolicy
            runtime.start(scope, settings.pollIntervalMs)
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(5000)
                    runCatching {
                        val next = bootstrapPolicy(policyStore)
                        val previous = watchedPolicy
                        if (next != previous) {
                            engine.policyChanged(next)
                            watchedPolicy = next
                        }
                        statusObserver.apply(engine.snapshot())
                    }.onFailure { Log.w("SuperagentRuntime", "status refresh failed", it) }
                }
            }
        }
    }

    /** Bridge 只做界面映射；Router 为什么拒绝、票建到哪一步，都要在这条边界上翻译给用户。 */
    private fun reportOutcomes(outcomes: List<com.wangzan101.mika.core.superagent.EvalOutcome>) {
        outcomes.forEach { outcome ->
            when (outcome) {
                is com.wangzan101.mika.core.superagent.EvalOutcome.TicketCreated ->
                    bridge.accepted(localConversation(outcome.ticket.threadKey), outcome.ticket.id, outcome.ticket.title)
                is com.wangzan101.mika.core.superagent.EvalOutcome.Attached ->
                    bridge.accepted(localConversation(outcome.ticket.threadKey), outcome.ticket.id, outcome.ticket.title)
                is com.wangzan101.mika.core.superagent.EvalOutcome.TicketStopped ->
                    bridge.report(localConversation(outcome.ticket.threadKey), outcome.ticket.id, outcome.ticket.title, "failed")
                is com.wangzan101.mika.core.superagent.EvalOutcome.Ignored ->
                    bridge.rejected(outcome.decision.triggerId, outcome.decision.reason.ifBlank { "ignored by router" })
                is com.wangzan101.mika.core.superagent.EvalOutcome.WouldAccept ->
                    bridge.rejected(outcome.triggerId, outcome.reason.ifBlank { "router is observing only" })
                is com.wangzan101.mika.core.superagent.EvalOutcome.NotAdmitted ->
                    bridge.rejected(outcome.triggerId, outcome.reason)
                is com.wangzan101.mika.core.superagent.EvalOutcome.TicketResumed -> Unit
            }
        }
    }

    private fun localConversation(threadKey: String) = threadKey.removePrefix("chat:")

    /** 只在从未配置过策略时给本机 Super 一个可用起点；之后完全以文件为准。 */
    private fun bootstrapPolicy(store: FilePolicyStore): com.wangzan101.mika.core.superagent.Policy {
        val loaded = store.load()
        val untouched = loaded == com.wangzan101.mika.core.superagent.Policy(version = DEFAULT_POLICY_VERSION)
        val policy = if (untouched) com.wangzan101.mika.core.superagent.Policy(
            version = "superagent-local#1",
            acceptMode = com.wangzan101.mika.core.superagent.AcceptMode.Auto,
            allowedActors = mapOf(LOCAL_SOURCE to listOf("owner")),
        ) else loaded
        if (policy != loaded) store.save(policy)
        return policy
    }

    private fun startInForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (_: IllegalArgumentException) {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun dataRoot(): File = File(filesDir, "superagent").apply { mkdirs() }
    private fun settingsFile() = File(dataRoot(), "settings.json").toPath()

    companion object {
        const val LOCAL_SOURCE = "local"
        private const val CHANNEL_ID = "superagent_runtime"
        private const val NOTIFICATION_ID = 3101

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, SuperagentRuntimeService::class.java)) }
                .onFailure { Log.w("SuperagentRuntime", "system blocked foreground start", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SuperagentRuntimeService::class.java))
        }
    }
}
