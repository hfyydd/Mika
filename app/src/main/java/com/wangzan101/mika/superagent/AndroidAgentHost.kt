package com.wangzan101.mika.superagent

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.RemoteException
import androidx.webkit.WebViewCompat
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.AppCopyReader
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.cap.phone.shell.ShellBinding
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.web.LazyWebPort
import com.wangzan101.mika.cap.web.WebStack
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.child.ChildPhase
import com.wangzan101.mika.core.child.ChildResult
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.resource.ResourceScope
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.shell.AccessibilityRepair
import com.wangzan101.mika.core.superagent.TaskHost
import com.wangzan101.mika.core.uistate.AppAccessScreenPort
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.cap.web.PagePacing
import com.wangzan101.mika.core.cap.phone.AppPacing
import com.wangzan101.mika.core.uistate.IdleScroll
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.task.TaskRecovery
import com.wangzan101.mika.settings.ApprovalControl
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ProviderStore as AppProviderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.locks.ReentrantLock

/**
 * 独立的 Android 执行端口:不改 TaskService,直接装配 Mika 的 AgentLoop/虚拟屏/浏览器/沙箱。
 * RunSpec 的 capabilities/readOnly 在这里被 ToolDispatcher 强制执行;远端来源不允许付款。
 */
class AndroidAgentHost(private val context: Context) : TaskHost {
    private val app = context.applicationContext as MikaApp

    override suspend fun run(spec: RunSpec): ChildResult {
        val conversationId = ConversationStore.shared(context).newConversation()
        val resources = ResourceScope()
        var connection: ShellBinding? = null
        var connectedScreen: AndroidScreen? = null
        var phoneScreen: LazyPhoneScreen? = null
        var display: LazyAgentDisplay? = null
        val recovery = TaskRecovery.of(context)
        try {
            val metrics = context.resources.displayMetrics
            val appAccess = app.appAccess
            val phoneServices = com.wangzan101.mika.cap.phone.PhoneServices.of(context)

            suspend fun connectPhone(): ScreenPort {
                val conn = connection ?: phoneServices.sharedShell.acquire().also { bound ->
                    connection = bound
                    resources.acquire(Dispatchers.IO, ShellBinding::unbind) { bound }
                }
                val shell = withTimeoutOrNull(15_000L) { conn.shell.filterNotNull().first() }
                    ?: throw IllegalStateException("phone helper unavailable")
                val shellRecovery = phoneServices.recovery
                val a11y = AccessibilityRepair.awaitService(
                    ScreenAccessibility.instance,
                    shellRecovery.accessibilityRepair.phase,
                    baseMs = 20_000L,
                ) ?: throw IllegalStateException("accessibility unavailable")
                recovery.beginRun(shell, spec.id.value)
                val created = resources.acquire(
                    Dispatchers.IO,
                    AgentDisplay::release,
                ) {
                    AgentDisplay(
                        shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                        context.getSystemService(DisplayManager::class.java),
                    )
                }
                val lazyDisplay = LazyAgentDisplay(metrics.widthPixels, metrics.heightPixels) { created }
                    .also { display = it }
                val screen = AndroidScreen(
                    a11y,
                    shell,
                    lazyDisplay,
                    clipHeld = { recovery.markClipHeld(it) },
                    clipLock = ReentrantLock(),
                )
                connectedScreen = screen
                val lazy = LazyPhoneScreen(
                    metrics.widthPixels,
                    metrics.heightPixels,
                    listApps = { withContext(Dispatchers.IO) { launchableApps(context.packageManager, context.packageName) } },
                    appCopies = {
                        phoneServices.hub.shell.value?.let { s ->
                            withContext(Dispatchers.IO) {
                                runCatching { AppCopyReader.read(s, launchableApps(context.packageManager, context.packageName)) }.getOrNull()
                            }
                        }.orEmpty()
                    },
                ) { screen }
                val guarded = AppAccessScreenPort(
                    lazy,
                    appAccess,
                    packages = { connectedScreen?.accessPackages().orEmpty() },
                    protectedUi = { appAccess.sensitiveUi },
                    ownPackage = context.packageName,
                    windowToken = { ScreenAccessibility.windowGeneration() },
                )
                return guarded
            }

            val phone = LazyPhoneScreen(
                metrics.widthPixels,
                metrics.heightPixels,
                listApps = { withContext(Dispatchers.IO) { launchableApps(context.packageManager, context.packageName) } },
            ) { connectPhone() }
            phoneScreen = phone
            val guardedPhone = AppAccessScreenPort(
                phone,
                appAccess,
                packages = { emptyList() },
                protectedUi = { appAccess.sensitiveUi },
                ownPackage = context.packageName,
                windowToken = { ScreenAccessibility.windowGeneration() },
            )

            val session = ModelSession.of(conversationId)
            val providers = AppProviderStore.get(context)
            val model = providers.modelFor(ModelRole.Main, session)
                ?: return ChildResult(spec.id, ChildPhase.Interrupted, "no main model")
            val light = providers.modelFor(ModelRole.Light, session)
            val vision = providers.modelFor(ModelRole.Vision, session)
            val flags = FeatureFlags.get(context).speedFlags()
            val memoryRuntime = MemoryRuntime.of(context)
            val switches = memoryRuntime.settings().switches.value
            val memory = if (switches.useMemory) withContext(Dispatchers.IO) { memoryRuntime.store() } else null
            val knowledge = withContext(Dispatchers.IO) {
                val notes = KnowledgeStore.loadNotes(java.io.File(context.filesDir, "personal"))
                KnowledgeStore(KnowledgeStore.loadBundled(), notes, emptyMap()) to GuideCatalog.loadBundled()
            }
            val sandbox = if ("sandbox" in spec.capabilities.orEmpty()) {
                AndroidSandbox(context, SandboxState.get(context))
            } else null
            val web = if ("web" in spec.capabilities.orEmpty() && runCatching {
                    WebViewCompat.getCurrentWebViewPackage(context) != null
                }.getOrDefault(false)
            ) {
                LazyWebPort {
                    val stack = resources.acquire(Dispatchers.Main, WebStack::close) {
                        val dirs = SandboxDirs(context)
                        WebStack.create(
                            context,
                            spec.id.value,
                            metrics.widthPixels,
                            metrics.heightPixels,
                            metrics.densityDpi,
                            workspace = WebStack.Workspace(dirs.workspace(conversationId), dirs.gate, dirs.tree),
                            keeper = null,
                        )
                    }
                    stack.screen
                }
            } else null
            val utterances = UserUtterances()
            val tools = toolDispatcher(
                guardedPhone,
                model.capabilities.vision,
                knowledge.second,
                vision = vision?.let { com.wangzan101.mika.core.vision.VisionService(it, com.wangzan101.mika.vision.BitmapImageOps) },
                web = web,
                webPacing = { PagePacing.human() },
                webIdleScroll = { IdleScroll(kotlin.random.Random.Default) },
                appPacing = { AppPacing.human() },
                appIdleScroll = { IdleScroll(kotlin.random.Random.Default) },
                workspacePath = web?.let { { path -> sandbox?.uploadCheck(conversationId, path) ?: throw IllegalStateException("sandbox unavailable") } },
                sandbox = sandbox,
                workspace = { conversationId },
                memory = memory,
                profile = memory?.let { memoryRuntime.profile() },
                utterances = utterances,
                conversationId = conversationId,
                runSpec = spec,
                personalSkills = memoryRuntime.skills(),
                runRecords = com.wangzan101.mika.record.RunStore(context).reader(),
            )
            val approvalControl = app.approvalControl
            val approval = approvalControl.start(remote = true)
            val agent = AgentLoop(
                model,
                tools,
                com.wangzan101.mika.core.agent.SystemPrompt.identity(),
                knowledge = knowledge.first,
                installedApps = { phone.listApps().map { it.first to it.second } },
                maxSteps = spec.stepBudget ?: AgentLoop.UNLIMITED_STEPS,
                confirmations = approval.gate(),
                approvals = approval,
                autonomous = false,
                confirmTimeoutMs = 5 * 60_000L,
                selfPackage = context.packageName,
                flags = flags,
                skills = knowledge.second,
                memory = memory,
                profileSnapshot = if (memory != null) withContext(Dispatchers.IO) {
                    runCatching { memoryRuntime.profile().snapshot() }.getOrDefault(emptyList())
                } else emptyList(),
                outwardPacing = com.wangzan101.mika.core.action.OutwardPacing.human(),
            )
            val end = agent.run(spec, com.wangzan101.mika.core.run.RunInbox())
            return when (end) {
                is TaskState.Succeeded -> ChildResult(spec.id, ChildPhase.Completed, end.answer)
                is TaskState.Failed -> ChildResult(spec.id, ChildPhase.Completed, end.reason)
                TaskState.Cancelled -> ChildResult(spec.id, ChildPhase.Interrupted, "cancelled")
                else -> ChildResult(spec.id, ChildPhase.Unknown, "no result")
            }
        } catch (e: RemoteException) {
            return ChildResult(spec.id, ChildPhase.Interrupted, "phone shell died")
        } finally {
            try {
                phoneScreen?.afterTask?.let { marks ->
                    display?.current?.let { d ->
                        connection?.shell?.value?.let { shell ->
                            withContext(Dispatchers.IO) { com.wangzan101.mika.task.AppsAfterTaskEnd.finish(shell, d.displayId, marks) }
                        }
                    }
                }
                recovery.endRun(spec.id.value)
            } catch (_: Throwable) {
            } finally {
                withContext(NonCancellable) {
                    runCatching { resources.close() }
                }
            }
        }
    }
}
