# Superagent 缺陷清单

> 只列毛病，不列优点，不写总体评价。
>
> - 审查基线：`d09eb8f4`（2026-11-05），工作树干净；行号均指该版本当前文件。
> - 范围：core 与 app 里的 superagent 源码（约 3000 行）、25 个测试类、`docs/dev/super-agent*.md`、`AndroidManifest.xml`。
> - 验证：`./gradlew :core:test --offline` → 337 个 suite / 2888 个用例 / 0 失败 / 3 跳过。全绿不代表正确，见「测试假绿」一节。
> - 编号规则：P0 阻断核心承诺，P1 正确性与越权，P2 中等缺陷，P3 死代码与文档不实，T 测试问题。

---

## 一、P0 阻断级（9 条）

- **[P0-01] 崩溃恢复的新 Assignment 永远不会被派发** — `core/superagent/SuperagentRuntime.kt:66`：`recover()` 的返回值被直接丢弃，`start()` 里只有孤零零一句 `recover()`。`Engine.kt:523/538` 新派的是 `Pending` 且无 `startedAtMs`，而看门狗 `Engine.kt:520` 只筛 `Running && startedAtMs != null`，永远不会再捞起它们。后果：进程死亡后未完成票永久卡住，`super-agent-plan.md` M2「杀进程续跑」不成立。
- **[P0-02] 持久副作用队列会互锁** — `core/superagent/EffectQueue.kt:104-105`：`drain()` 取全局 `minByOrNull { seq }` 的 Pending，遇到未到期记录直接 `break`；`Runner.kt:25-31` 与 `SourceOutbox.kt:53-57` 的 lambda 对任何非目标 id `error("queue is blocked by ...")`。只要队列里存在一条其它记录（退避中的回复、崩溃残留、另一张票的 Assignment），当前副作用要么不执行、要么被当作异常连烧 3 次变 `Failed`。设计 2.2「杀进程后不丢不重」在这些队列状态下直接失效。
- **[P0-03] 报告丢失仍上报成功** — `core/superagent/Runner.kt:34`：`runAssignment()` 无论 `report` 是否为 null 都 `return true`，而注释写「返回 true 表示报告已记录」。配合 P0-02 中 `drain` 直接 `break` 的路径，调用方认为票已完成，`Engine.report` 却从未被调用。
- **[P0-04] 恢复会重放外部动作，且回复通道拿不到幂等键** — `EffectQueue.kt:88-91` 把崩溃时的 `Attempting` 无条件放回 `Pending`，其 action 是 `Runner.runAndReport()`，即重跑整个 AgentLoop（手机操作、网页、沙箱）；`SourceOutbox.kt:21-23` 的 `ReplyChannel.send(envelope)` 接口签名里没有 `EffectRecord.id`，注释却要求「凭 EffectRecord.id 幂等」。本地通道 `SuperagentLocalReplyChannel.kt:20-33` 用固定 `runId = "super-$ticketId"` 调 `ConversationStore.append()`，而 `chat/ConversationStore.kt:314-323` 是无条件追加、不查重。后果：崩溃在「已发送、未写 Done」窗口即重复发消息、重复执行外部动作，`updateTurn(runId)` 还会同时改到多条同 runId 记录。与 `super-agent.md:34`「Executor 靠自查恢复，不盲目重放外部动作」相反。
- **[P0-05] 前台服务在 Android 15 上撑不过 6 小时** — `app/src/main/AndroidManifest.xml:180` 声明 `foregroundServiceType="dataSync"`，`SuperagentRuntimeService.kt:182` 传 `FOREGROUND_SERVICE_TYPE_DATA_SYNC`，`app/build.gradle.kts:20` 是 `targetSdk = 35`。Android 15 对 dataSync 有 24 小时内累计 6 小时配额，超时回调 `Service.onTimeout(int,int)`，而本服务全类没有覆写 `onTimeout`（全目录 grep 无匹配），也没有 `stopSelf()` 兜底。更刺眼的是项目自己在 `task/TaskService.kt:83` 写着「dataSync 在安卓 15 有每日 6 小时上限且语义不符；specialUse 没有运行时限制」，并已改用 specialUse，Super 服务却仍用 dataSync。提交 `a8460b6c` 声称让前台运行时可靠，方向相反。
- **[P0-06] 冷启动 / 开机 / 强停后服务不会被拉起，开关显示「开」而功能静默失效** — 全仓 `SuperagentRuntimeService.start()` 的唯一调用点是 `ui/SuperagentScreen.kt:50`；`MikaSuperRuntimeHost.kt:35` 的 `superMode = _userOn && bridge.enabled`，`bridge.enabled` 在新进程恒为 `false`。`MikaApp.onCreate()` 与 `setup/BootRestoreReceiver.kt` 均不拉起服务。提交 `d09eb8f4` 只恢复了 `_userOn` 的文件初值，提交说明里自己写着 `Rejected: Start the runtime service on app launch`。后果：开机、应用更新、用户强停、P0-05 超时之后，UI 显示开启、消息静默回退普通 Mika 路径，无任何提示。
- **[P0-07] 状态文件损坏被静默当成空状态，随后覆盖原文件** — `core/superagent/Store.kt:43-49`：解码异常一律 `catch → SuperagentState()`；`EffectQueue.kt:47-53`、`SuperagentSettings.kt:29-33`、`PolicyStore.kt` 同型回退。一次半截写入、磁盘错误或新增枚举值导致的不兼容，会让 tickets / assignments / dispositions / readPositions / 待执行副作用 / 开关全部归零且无日志，下一次 `save()` 直接把损坏文件覆盖掉。对照 `chat/ConversationStore.kt:318-321`，那里至少会把损坏文件改名 `.corrupt-*` 留证，Super 这边连这一步都没有。
- **[P0-08] 单守护循环同步跑完整张票，轮询、看门狗、其它票全部饿死** — `SuperagentRuntime.kt:69-73`：`pollOnce → evaluateOnce → dispatch → watchdogOnce → outbox.flush` 串行在同一个协程里，`dispatch` 是同步 `runner.runAssignment()`，内部 `executor.run()` 跑完整 AgentLoop，且 `AndroidAgentHost.kt:218` 用 `maxSteps = spec.stepBudget ?: AgentLoop.UNLIMITED_STEPS`（无限步）。一张长票执行期间：新消息不进 Engine、看门狗形同虚设、回复不发送、其它线程全停。设计里「至多一个 Eval 在跑」被放大成「整个 Runtime 卡在单票」。
- **[P0-09] Router 判决完全没有宿主校验，可跨线程越权** — `Engine.kt:338/356` 用 `state.tickets.firstOrNull { it.id == decision.ticketId }` 全局按 id 找票，不校验 `threadKey`、不校验发起人是否在名单、不校验票是否 open。`Stop` 一律写 `by="person"`（`:362`）导致永不恢复，`Attach` 把 `status=Open, ended=null` 强行写回（`:345`），连被人停止的票都能复活。`decision.ticketId` 来自 LLM 自由文本（`Router.kt:102-103`），`Router.kt:49` 还把外部消息 `text.take(600)` 无转义拼进 prompt。后果：任何通过门禁的消息（含 bot / inferred）经 prompt injection 或直接让模型输出 `stop` + 任意 ticketId，即可停掉别人的票；`attach` 可绕过「人的停止不可恢复」。

## 二、P1 严重（10 条）

- **[P1-01] inferred 路径的「宿主校验」是模型自填的布尔** — `Engine.kt:291-298` 检查 `matchedRule/direction/motivationRef/hostVerified` 四要素，但 `hostVerified` 来自 `Router.kt:100` 的 `HOST_VERIFIED_PATTERN` 正则解析，即模型自己输出 `true` 就算通过；四要素全部由模型自填且无宿主侧核对。设计 1.8「推断接受缺任一要素被宿主拒绝」形同虚设。显式路径（`Strength.Explicit`）连这四要素都不查，也接受任意 `scope`。
- **[P1-02] scope 翻译层 fail-open，认不出就授予全部能力** — `core/superagent/TaskHostExecutor.kt:66-75`：`mikaRunScope()` 用子串猜能力，`.ifEmpty { setOf("phone", "web", "sandbox") }`，`readOnly` 也只在命中关键词时才为 true，且 `Regex("\\b(...|summar|analyze)\\b")` 匹配不到 "summarize"/"analysis" 这类常见词。`Router.kt:95` 在模型不给 scope 时默认 `""`。澄清：`core/assembly/RunAssembler.kt:28-37` 确实按 `spec.capabilities/readOnly` 过滤工具，所以问题不是「完全不约束」，而是**翻译层把「看不懂」默认翻译成「全能力 + 可写」**，等于授权范围由 LLM 的一句话决定。
- **[P1-03] 执行失败被逐级映射成成功完成** — `AndroidAgentHost.kt:235`：`TaskState.Failed -> ChildResult(..., ChildPhase.Completed, end.reason)` → `TaskHostExecutor.kt:50` → `ExecutorReport(Ready, outcome="done")` → `Engine.kt:498-510` 把 Ticket 标为 `Ended`。模型报错、工具失败、无主模型（`:154` 返回 `Interrupted` 另算）最终被当成正常完成，票不再保持 open 可重试，用户收到失败原因字符串当结果。与 `super-agent.md:33-34` 相反。
- **[P1-04] `policyChanged` 在生产里零调用** — 全仓 grep `policyChanged(` 只命中 `Engine.kt:551` 与 2 个测试文件。`SuperagentRuntimeService.kt:117` 每轮只 `policyStore.load()` 更新门禁，从不触发 Mandate 重验。移除 actor、停来源、弱化准入表、把模式改成 Observe 之后，已开放票的旧授权继续有效。设计 1.10 / 计划 4.1「改配置即改门禁」未接线。
- **[P1-05] 被 Policy 撤销的票会被人的后续消息复活** — `Engine.kt:557` 撤销写 `ended.by = "policy"`，但 `core/superagent/Ownership.kt:22` 的 `followupOwners` 只排除 `by != "person"`，`Engine.kt:50-51` 的复活分支也只判断 `person`。后果：撤销票仍算 owner，一条跟进消息就把它恢复为 Open，`policyVersion`/scope/strength 全是旧的且不重新重验，被撤销的授权重新生效。
- **[P1-06] 500 条窗口破坏 externalId 幂等** — `Engine.kt:38` 判重只扫 `state.triggers.any { ... }`，而 `:83` 对 triggers 做 `takeLast(MAX_TRIGGERS=500)` 裁剪；`dispositions` 账本（不受裁剪）没被用于判重。长跑后历史事件再次投递会被当新事件，重复入库、重复开票，设计 1.6「同消息双开票不可能」失效。
- **[P1-07] 窗口裁剪后原文召回与回复双双失效，兜底是死代码** — `Runner.kt:73` 取不到 trigger 直接 `return` 丢回复；`TicketPanel.kt:38` 的 `mapNotNull` 让票详情输入凭空消失；app 源码里 `TicketHostTaskExecutor.kt:24-25` 的兜底 `FileTriggerArchive.read()` 在全仓**没有任何写入点**（`trigger-archive.jsonl` 永远不存在），于是 `TaskHostExecutor.kt:28` 回退成 `ticket.instruction.removePrefix("trigger:")`，**把 trigger ID 当原始指令**交给 AgentLoop，违反设计「Executor 自己读原文」。
- **[P1-08] 桥用三把不同的锁保护同一批哈希表，StateFlow 更新无锁** — `app/.../SuperagentBridge.kt:47/60/86/101`：`accepted()` 在 `synchronized(tickets)` 里改 `pendingTriggers` 和 `pendingGoals`，`send()` 却在 `synchronized(pendingTriggers)` 里写同一个 map，`rejected()` 又用第三把锁；`set()`（`:118`）是 `target.value = transform(target.value)` 非 CAS。写者分别来自 UI 主线程与 Service 的 `Dispatchers.Default`，存在哈希表结构损坏、状态覆盖丢失。
- **[P1-09] 本地 Trigger 只在内存，先清队列再处理，异常即整批丢** — `app/.../SuperagentLocalSource.kt:39-44`：`poll()` 完全忽略传入的 `state`，先 `queue.clear()` 再返回；`SuperagentRuntime.kt:32-36` 的 `catch (_: Exception)` 吞掉异常但队列已空，`engine.receive()` 单条抛错会让整批 Trigger 消失。进程在 `send()` 与 `poll()` 之间死亡同样丢事件。设计 `:11/:39` 的「游标存进状态、重启不重放」在本地来源上根本没有——`readPositions` 只有 `Engine.receive` 写、`TelegramSource` 读。
- **[P1-10] App 侧对核心模块零接线，Phase 3 打勾与真机不符** — `app/src/main/java` 全目录 grep：`TelegramSource`、`TelegramReplyChannel`、`TicketPanel`、`PolicyEditor`、`ObservationLog`、`SuperagentNotifications`、`RoutineTickets` **零引用**；`SuperagentRuntimeService.kt:106/118` 只装了 `chat:local` 一个通道和 `localSource` 一个来源。计划 Phase 3.1/3.2/3.5 全部打勾、M3「Telegram @ 它，群里有回音」在真机上不可能达成。

## 三、P2 中等（22 条）

- **[P2-01]** `SuperagentRuntime.kt:55-61`：`dispatch()` 的 `if (effects == null)` 与 else 分支代码完全相同，构造时传入的 `effects` 不参与派发；计划 2.2「新票交给持久效果队列」没有实现。
- **[P2-02]** `SuperagentRuntime.kt:66/76`：`recover()` 在 `try` 之外，读盘或反序列化抛异常会让整个守护协程失败；循环内 `catch (_: Exception)` 空实现无日志。`SuperagentRuntimeService.kt:124-129` 的 1 秒状态观察协程也是裸 `while(true)`，无异常处理。故障模式是「开关开着、卡片转圈、什么也没发生」，且无可观测信号。
- **[P2-03]** `SuperagentRuntimeService.kt:160-164`：每轮 `mergePolicy` 硬编码把 `"owner"` 塞回 `allowedActors["local"]`，用户在策略文件里删掉它也没用，与「改配置即改门禁」相悖。
- **[P2-04]** `bridge/MikaSuperRuntimeHost.kt:24-31`：`onStarted()/onStopped()` 直接写 `_userOn`，而 `_userOn` 本应只由用户开关与文件驱动；`Service.kt:70-72/84-86` 的生命周期回调于是能改写用户开关。`ui/SuperagentScreen.kt:44-51` 的 `setEnabled()` 只改内存不写文件，文件由 UI 单独写。后果：服务被系统回收或超时停止后，`settings.json` 仍是 `enabled=true`、UI 显示「开」，`superMode` 实际为 false，必须重启进程或手动拨两次开关。
- **[P2-05]** `SuperagentScreen.kt:33-35/44-51`：`settings` 初值是默认对象、`LaunchedEffect` 异步 `load()`，加载完成前拨开关会用默认值覆盖 `pollIntervalMs`；连点开关会并发 `store.save` / `start` / `stop`，`Dispatchers.IO` 写入乱序，文件与运行态可能不一致。
- **[P2-06]** `SuperagentRuntimeService.kt:124-129` + `SuperagentStatusObserver.kt:15-26`：`engine.snapshot()` 每次重新读盘反序列化整个 JSON，每秒一次；observer 注释写「只在账本变化时更新」，实际无 diff、遍历 `state.tickets` 全部历史票（含已结束），且每次 `bridge.report` 生成新 Map 触发 UI 重组。进程重启 `reset()` 清空 tickets 后，`report()` 还会按最老的历史票重新绑定每个对话，旧对话冒出幽灵 Super 卡片。
- **[P2-07]** `SuperagentBridge.kt:63/79`：`pendingGoals` 是 `triggerId → 用户原文`，`accepted()` / `clear()` 却用 `pendingGoals.values.removeAll(setOf(conversationId))` / `remove(conversationId)` 按 conversationId 删值，永不命中，条目永久泄漏。（`pendingTriggers` 的值就是 conversationId，那边是对的，别混为一谈。）
- **[P2-08]** `core/superagent/Runner.kt:48`：`catch (e: Exception)` 会把 `CancellationException` 吞掉并写成 `ReportState.Failed`，不重抛取消信号；同项目 `SuperagentRuntime.kt:74-75`、`EffectQueue.kt:115` 都单独处理了取消，唯独 Runner 漏了。
- **[P2-09]** `Engine.kt:498-513`：`ReportState.Ready` 但 `outcome` 不是 done/merged/closed 时，`endOutcome = null`，Ticket 保持 Open，而 Assignment 已置 `Ended`；`watchdog` 只挑 `Running`（`:520`），票永久卡死无人重派。
- **[P2-10]** `EffectQueue.kt:73/81`：`seq` 在构造时固定为初始最大值，新记录 seq 用 `seq + store.all().size + 1`，`firstOrNull` 幂等检查与 `save` 之间无锁；并发 submit 会产生相同 seq，`minByOrNull` 失去保序依据。`MemoryEffectStore` 的 `@Synchronized` 只保护单次调用。
- **[P2-11]** `Store.kt:52-57`、`EffectQueue.kt:61`、`SuperagentSettings.kt:36`：三个文件存储都写固定的 `path + ".tmp"` 再 `ATOMIC_MOVE`；`FileEffectStore.save()` 还是「改内存 → 全量 all() → 写文件」两段独立同步调用。并发写会互相覆盖快照，`Engine` 的 `load() → save()` 读-改-写本身也不是事务。
- **[P2-12]** `EffectQueue.kt:94/121`：`due()` 在 main 里没有任何调用点（`drain` 自己重写了一套语义不同的过滤）；达到 `maxAttempts` 后记录变 `Failed`，无重试、无告警、无面板入口，一条 Reply 或 Assignment 副作用永久丢失且无人知晓。
- **[P2-13]** `SourceOutbox.kt:44/52` + `SuperagentLocalReplyChannel.kt:18-19`：effect id 用 `payload.hashCode()`（可碰撞，两条不同结果可能被误去重）；`channels[key] ?: continue` 让未配渠道的回复永久滞留无告警；本地通道对非 chat 来源返回 `""`、对空文本返回 `"empty:local"`，`EffectQueue.drain` 把空字符串当成功凭据写 `Done`。`SourceOutbox.kt:55` 的 `row.state == Done` 分支因 `drain` 只取 Pending 而永不可达。
- **[P2-14]** `Admission.kt:46` + `Engine.kt:165/596`：`admissionTable(sourceInstance, scope="*")` 的两个生产调用点都不传 scope，等价于永远读 `"*"`/`DEFAULT`；而 `PolicyEditor.kt:48/68` 的 `setCell` 会把编辑结果写到具体 scope 的格子里。后果：按 scope 收紧准入表完全不生效，带 scope 的分支只在 `AdmissionTableTest.kt:32-34` 被测到，是死代码。
- **[P2-15]** `Engine.kt:79-80`：`admittedAtMs` / `evalDeadlineAtMs` 用 `trigger.atMs`（来源事件时间）计算，`TelegramSource.kt:84` 的 `atMs` 是 Telegram 服务端秒级时间戳，缺 `date` 时为 `0`；本机时钟偏差或缺失 date 会让候选立即过期（两轮重排后 `unavailable`）或长期不超时。设计 1.5 要求「候选送达起算 deadline」。
- **[P2-16]** `Engine.kt:87` + `TelegramSource.kt:40`：`readPositions` 的 key 只写 `trigger.source.instance`，不含 `kind`。同名 instance、不同 kind 的两个 Source 共用并互相覆盖游标。
- **[P2-17]** `TelegramSource.kt:54/57-68`：`poll` 用 `mapNotNull(::trigger)` 丢掉无 text / 无 from / 无 chat.id 的 update，但游标由 `Engine.receive` 逐条写、只记成功 Trigger 的 `externalId`。一批里只要最后一条是图片、贴纸或 `channel_post`（`allowed_updates` 明确包含它，且通常没有 `from`），cursor 就停在它前面，下次 `offset` 仍指向这条坏 update，长轮询永久卡死，后续所有消息收不到。
- **[P2-18]** `TelegramSource.kt:22-24/88-94`：`allowFrom` 与 `botUsername` 在生产代码中零读取（全仓仅定义处与一个测试构造出现）；`hasMention` 只要 entity 类型是 `mention`/`text_mention`/`bot_command` 就返回 true，不校验是不是 `@botUsername`，群里任意 `@otheruser` 或 `/command` 都算「点名了 agent」。`caption` 有文本也不读，`is_bot` 缺失按人处理，`atMs` 缺 date 为 0。设计 3.2 的 allow_from 名单与 @ 边界都没落地。
- **[P2-19]** `Router.kt:79-88` + `Engine.kt:136-138`：`parse` 按第 i 个 `triggerId` 出现的位置切窗口，不校验该 triggerId 的值；模型少输出一条时，后续 trigger 被判 `router output missing entry` 的 `Ignore`，然后整个线程队列被无条件清空，不重试、不报警。与设计「Router 不可用时该批留在队列里下次再试」不符。
- **[P2-20]** `ObservationLog.kt:14-20` + `Engine.kt:573-574`：`promote()` 不看 `state.observations`，只查 `state.triggers`，却把固定串 `"$kind:$instance:addressed"` 写进 `enabledRules`；一旦该 key 非空，重验会对每张开放票检查 `matchedRule !in enabled`，而票的 `matchedRule` 永远不会等于这个固定串 → 下次 `policyChanged` 把该来源所有开放票判 `rule_disabled` 撤销。注释「Missing audit references are intentionally ignored」与代码不符，任意 triggerId 都能触发这条链。
- **[P2-21]** `TicketPanel.kt:11/37`、`SuperagentNotifications.kt:15`、`RoutineTickets.kt:46`：都用 `maxByOrNull { it.reports.size }` 选「当前」Assignment。看门狗重派后旧 Assignment 报告多、新的是 0 → 面板/通知/Goal 同步全读旧状态；`TicketPanel.rows` 的置顶却用 `any(::waitsForPerson)`（任一 Assignment），会出现「面板说需要人、通知却不发」。另 `TaskHostExecutor.kt:41` 的 `RunId("superagent-${ticket.id}")` 对同一票所有 Assignment 固定不变，TaskCenter 按 RunId 去重时新 Assignment 可能无法真正启动。
- **[P2-22]** app 里的 superagent 全目录没有唤醒锁和电池优化引导（Manifest 声明了 `WAKE_LOCK`，`task/TaskRun.kt` 有 `KeepAwake`，Super 没有）；`AndroidAgentHost.kt:251` 的 `catch (_: Throwable)` 连取消异常一起吞；`:211` 用 `approvalControl.start(remote = true)`、`:221` 用 `autonomous = false`，与 `RunProvenance(RunOrigin.Trigger, Attendance.Unattended)` 的无人值守定位冲突；`SuperagentRuntimeService.kt:74` 只 catch 服务启动态异常、`:183-184` 只 catch 前台参数异常、`:196-198` 的 `start()` 不捕获系统禁止前台启动异常，任一都可能让开关界面崩溃或配合 START_STICKY 循环重启。

## 四、P3 死代码、孤儿模块与文档不实（12 条）

- **[P3-01]** `docs/dev/super-agent-plan.md`：Phase 1、2、4 全勾，Phase 3 只留 3.3 飞书 / 3.4 通知来源未勾，Phase 5 只留 5.2/5.3 未勾；但按 P1-10、P1-04、P0-01 的核查，「core 已实现」被当成了「已装配验收」，4.1/4.2/4.3/5.1 验收栏自己也写着「UI 待接入」却仍打勾。Phase 2.5 声称「删除 core 的 autonomy 模块」，而 core/src/main/kotlin/.../autonomy 五个文件仍在且被 `RoutineTickets.kt:3-4` import。
- **[P3-02]** `super-agent.md:39` 声称「全部状态 JSON 快照，原子替换写入」——`ATOMIC_MOVE` 只保证单文件不半截，不保证 P2-11 的并发读-改-写；`super-agent.md:34` 声称「Executor 靠自查恢复，不盲目重放外部动作」——P0-04 证明无自查逻辑。
- **[P3-03]** `SuperagentRuntimeService.kt:175-180` 的前台通知只有 App 名、无 ContentText、无类别、无停止/设置动作，用户无法从通知关闭 Super；计划 4.3「通知可关，不扰」未实现。`onCreate()` 无条件 `startForeground()`，开关关闭时误调 `start()` 会闪一条常驻通知。
- **[P3-04]** `core/superagent/Replay.kt:17-24`：`ReplayOutcome` 只含 kind / triggerId / ticketId / strength / basis / reason，不含 scope、title、matchedRule、decisions、dispositions、readPositions。改坏 scope 映射、账本、报告状态机、重验、Store 封顶、Runner/EffectQueue 都不会让回放变红，设计 1.11「领域层改动破坏回放即测试红」不成立。`Replay.router`（`:40`）用 `getValue` 缺项抛异常，会被 `Engine.evaluateDue` 的 `catch` 吞成「Router 不可用」后静默返回空 outcomes。
- **[P3-05]** `core/src/test/resources/superagent/replay.jsonl` 三个样例（explicit / inferred / machine）全是单 trigger 的 `TicketCreated`，没有 Ignore/Attach/Stop/WouldAccept/NotAdmitted，也没有同线程多 trigger 批次；`ReplayTest.kt:67` 名为「byte identical」的断言是 `assertEquals(text, Replay.encode(Replay.decode(text)))`，即 JSONL 序列化往返，与回放输出无关。真正的回放比对（`:55-56`、`:71`）只覆盖 `ReplayOutcome` 那 6 个字段的 data class / 编码字符串相等，而这 6 个字段正好不含 P3-04 列出的任何一项。
- **[P3-06]** `core/superagent/TelegramReplyChannel.kt`：`ReplyEnvelope.deliverHere` 与 `triggerText` 完全未使用，永远发回 `trigger.threadKey`，设计 3.5「原路返回 / 主动推送」分支未落地；`text.take(4096)` 按 UTF-16 char 截断，可能切坏 emoji 代理对。`LlmRouter.assess`（`Router.kt:53`）直接调 `model.complete`，无 token 记账，计划 2.4「每次判决计入用量」未实现。
- **[P3-07]** `core/superagent/PolicyEditor.kt:16-23`：`addActor` 做 `trim` 而 `removeActor` 不做，带空格的 actor 删不掉；只有 `enableRule` 没有 `disableRule`，`enabledRules` 一旦非空无法回到「全部可用」；`activeSources` 没有编辑入口，而设计 1.10 的重验依赖它。
- **[P3-08]** `core/superagent/RoutineTickets.kt:10-13`：`trigger()` 的 `id = "routine:${goal.id}"` 固定，`externalId` 却含 `updatedAtMs`，Goal 多次更新生成多条同 id、不同 externalId 的 Trigger，`dispositions`/`decisions` 的 Map 互相覆盖，`firstOrNull { it.id == ... }` 永远取到最旧原文；参数 `policyVersion` 在函数体内未使用。
- **[P3-09]** 未接线/不可达代码：`SuperagentBridge.kt` 的 `clear()` / `statusFor()`、`SuperagentLocalSource.kt:18-19/51-53` 的 `history` / `clearArchived()`、`FileTriggerArchive.read()`、`SourceOutbox.kt:55` 的 Done 分支、`SuperagentRuntime.kt:55-61` 两个内容相同的 `dispatch` 分支，均无生产调用点或永远走同一条路。
- **[P3-10]** `bridge/SuperagentRuntimeHost.kt`：静态 `listeners` 是无同步的 `mutableListOf`，注册后从不 `unregister`，listener 抛异常会连带让 `onStartCommand` 崩溃；文件里还有未使用的 `import android.content.Intent`。`MikaSuperRuntimeHost.kt:36` 的 `stateIn()` 自建了一个永不取消的 `CoroutineScope(SupervisorJob()+Default)`。`SuperagentBridge.kt:94-95` 有两行重复注释。
- **[P3-11]** `core/superagent/EffectQueue.kt:121` 的 `Failed`、`SourceOutbox.kt:52` 的渠道缺失、`Engine.kt:122-125` 的 Router 异常、`SuperagentRuntime.kt:76` 的循环异常、`SuperagentRuntime.kt:34` 的来源异常——五处失败路径全部静默，没有日志、没有计数、没有面板可见性。
- **[P3-12]** `Engine.kt:100-107`：`batch.isEmpty()` 时（例如 trigger 已被 500 窗口裁掉）直接清空该线程队列，事件与 queuedTriggerIds 一起消失，dispositions 里不补任何记录；`dismissUnavailable`（`:237-245`）想从 threads 移除 id，但 `manageEvalSchedule:181` 早已把 unavailable 的 id 排除在 retainedIds 之外，这个移除实际是空操作。

## 五、T 测试假绿 / 覆盖缺口（12 条）

- **[T-01]** `StoreTest.kt:31-35` 名为 `corrupt file loads empty state`，把「损坏 → 静默清空全部账本」写成断言；`PolicyStoreTest.kt`、`SuperagentSettingsTest.kt` 同型回退也被命名为「safe closed policy」。
- **[T-02]** `EffectQueueTest.kt:35-40` 把 head-of-line blocking（第一条退避导致已到期的第二条不执行）写成 `assertEquals(0, completed)` 的预期，P0-02 的 bug 被测试锁死。
- **[T-03]** `EffectQueueTest.kt` 的 `survives attempting process death` 用 `drain` 抛异常后写回 Pending 来模拟，文件里根本没有 `Attempting` 记录，`recover()` 分支实际未被触发。
- **[T-04]** `SuperagentRuntimeTest.kt:54-67` 只断言 `recover()` 返回新 id、旧 assignment 变 Ended，没有断言新 Assignment 被 dispatch 执行；两个测试拼起来看似覆盖恢复，P0-01 的关键一环缺失。
- **[T-05]** `EngineTest.kt:444-457` 用 `threadKey="t2"` 的消息 attach 到 `t1` 的票并断言成功，把 P0-09 的跨线程越权固化成正常功能。
- **[T-06]** `DecisionPathTest.kt` 用桩 `copy(hostVerified=false)` 验证「缺要素被拒」，没有测模型自填 `true` 的绕过；`OwnershipTest.kt` 没测 `endedBy="policy"` 的复活绕过；`MandateRevalidationTest.kt` 全在直接调 `policyChanged`，掩盖了生产零接线。
- **[T-07]** `TaskHostExecutorTest.kt` 只测了能匹配到关键词的 scope（`"read the web page"`、`"use the phone app"`），没有 `mikaRunScope("")` 的 fail-open 用例，也没有失败映射用例。
- **[T-08]** `EffectQueueTest.kt`、`RunnerOutboxTest.kt`、`SourceOutboxTest.kt` 全部使用「队列里恰好只有目标一条」的桩；`SourceOutboxTest.kt:27-32` 还用 `if (sent.isNotEmpty()) error("offline")` 的过强桩人为阻止重复发送，掩盖「send 成功但写 Done 前崩溃 → 重放 → 重复发」。
- **[T-09]** `TelegramSourceTest.kt:44-59` 手工构造 `SuperagentState(readPositions=...)` 传进 `poll`，绕过了 `Engine.receive` 写游标这条真实链路；只有 private + mention、MentionOnly 两个用例，没有坏 update（无 text/from）、网络失败、`ok=false`、非 JSON、`allowFrom`、`botUsername`、`@otheruser`、`bot_command`、`channel_post`、`/task` 任何边界。
- **[T-10]** `AdmissionTableTest.kt:32-34` 测的带 scope 分支是生产走不到的死代码（P2-14）；`ReplayTest.kt` 只有 3 条单 trigger 样例，无多判决类型、无多 trigger 批次、无 Observe/Off/重验路径。
- **[T-11]** 全仓没有用例覆盖：500 条裁剪后的 externalId 幂等失效、`Runner.reply` 丢回复、`TicketPanel.detail` 丢输入、新增枚举值的解码、并发 `save` 的固定 `.tmp` 竞争、`Ready` 但 outcome 为空的卡死、`Router` 输出缺项。
- **[T-12]** 2888 个用例 / 337 个 suite 全绿，其中 superagent 只有 25 个测试类；绿的是「Happy path + 自己构造 expected + 直接改 store」的桩，不是「队列里还有别的记录 / 进程真死过 / 模型输出了恶意字段」这些真实路径。

---

## 六、修复依赖顺序（只列编号）

1. **P0-05 → P0-06**：服务能否长期、可靠地存在（换 specialUse 或覆写 `onTimeout`；冷启动按文件幂等拉起）。
2. **P0-01 → P0-02 → P0-03 → P0-04**：恢复链路与副作用队列（`recover()` 结果接 `dispatch`；`drain` 按 id/kind 分流、跳过未到期而非 break；副作用幂等键下传到渠道）。
3. **P0-07 → P2-10 → P2-11**：持久化正确性（损坏文件归档不覆盖、seq 原子分配、单锁事务化 `update`）。
4. **P0-08 → P1-08 → P2-02**：并发结构（dispatch 独立协程、桥统一一把锁 + `StateFlow.update`、异常可观测）。
5. **P0-09 → P1-01 → P1-02 → P1-03 → P1-05 → P1-04**：授权与结果语义（判决宿主校验、scope 白名单 fail-closed、Failed 不映射成 done、撤销票不可复活、策略变更触发重验）。
6. **P1-06 → P1-07 → P1-09 → P2 其余**：窗口/原文/来源/UI 一致性。
7. **T-01 ~ T-12**：把上述每条 bug 先写成会红的测试，再改代码；否则修复会继续被现有断言挡回去。
8. **P3 系列**：清理死代码、校正 `super-agent-plan.md` 的勾选口径，最后处理。
