package com.wangzan101.mika.superagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.wangzan101.mika.core.superagent.LlmRouter
import com.wangzan101.mika.core.superagent.Runner
import com.wangzan101.mika.core.superagent.SourceOutbox
import com.wangzan101.mika.core.superagent.SuperagentRuntime
import com.wangzan101.mika.core.superagent.SuperagentSettings
import com.wangzan101.mika.core.superagent.TelegramReplyChannel
import com.wangzan101.mika.core.superagent.TelegramReplyConfig
import com.wangzan101.mika.core.superagent.TelegramSource
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
 * 分身层的宿主服务:只装配 core 的 Runtime 与 Mika 的执行端口;不接管 TaskService,也不改既有任务链。
 * 本地聊天不需要 Telegram 配置；Telegram 只在用户配置后作为可选来源叠加。
 */
class SuperagentRuntimeService : Service() {
    private val bridge by lazy { SuperagentBridge.get(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val localSource = SuperagentLocalSource()
    private val statusObserver = SuperagentStatusObserver(this)
    private var runtime: SuperagentRuntime? = null

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
        val policyStore = FilePolicyStore(root.resolve("policy.json").toPath())
        val model = ProviderStore.get(this).modelFor(ModelRole.Light, ModelSession.of("superagent-local"))
            ?: ProviderStore.get(this).modelFor(ModelRole.Main, ModelSession.of("superagent-local"))
            ?: throw IllegalStateException("super-agent router model unavailable")

        val engine = Engine(
            store = FileSuperagentStore(root.resolve("state.json").toPath()),
            router = LlmRouter(model),
            executor = null,
        )
        val effects = EffectQueue(FileEffectStore(root.resolve("effects.json").toPath()))
        val channels = buildMap {
            settings.telegram?.takeIf { settings.telegramReady }?.let { telegram ->
                put("telegram:${telegram.instance}", TelegramReplyChannel(
                    TelegramReplyConfig(telegram.instance, telegram.token, telegram.baseUrl),
                ))
            }
        }
        val localOutbox = SourceOutbox(effects, channels +
            ("chat:${LOCAL_SOURCE}" to SuperagentLocalReplyChannel(this)))
        val telegram = settings.telegram.takeIf { settings.telegramReady }
        val runner = Runner(
            engine,
            TicketHostTaskExecutor(this) { engine.snapshot() },
            effects,
            localOutbox,
        )
        runtime = SuperagentRuntime(
            engine = engine,
            runner = runner,
            policy = { mergePolicy(policyStore.load(), settings, localSource) },
            sources = listOfNotNull(localSource, telegram?.let { TelegramSource(it) }),
            effects = effects,
            outbox = localOutbox,
        ).also { runtime ->
            runtime.start(scope, settings.pollIntervalMs)
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    statusObserver.apply(engine.snapshot())
                }
            }
        }
    }

    private fun mergePolicy(
        policy: com.wangzan101.mika.core.superagent.Policy,
        settings: SuperagentSettings,
        localSource: SuperagentLocalSource,
    ): com.wangzan101.mika.core.superagent.Policy {
        var merged = policy
        settings.telegram?.takeIf { it.allowFrom.isNotEmpty() }?.let { telegram ->
            val current = merged.allowedActors[telegram.instance].orEmpty()
            if (telegram.allowFrom.any { it !in current }) {
                merged = merged.copy(allowedActors = merged.allowedActors +
                    (telegram.instance to (current + telegram.allowFrom)))
            }
        }
        val localActors = merged.allowedActors[LOCAL_SOURCE].orEmpty()
        if ("owner" !in localActors) {
            merged = merged.copy(allowedActors = merged.allowedActors +
                (LOCAL_SOURCE to (localActors + "owner")))
        }
        return merged
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
            context.startForegroundService(Intent(context, SuperagentRuntimeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SuperagentRuntimeService::class.java))
        }
    }
}
