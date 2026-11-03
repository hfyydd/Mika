# Super-agent 层(设计)

按 DeepSeek 2026-10 的 super-agent 设计([原型仓库](https://github.com/deepseek-ai/dsh-superagent-prototype))在 Mika 上实现一套多事件来源、可长期自主干活的层。Mika 本体(主循环、手机/网页/沙箱能力)是**底层执行支撑**:这一层决定「做什么、授权多大、谁接着做」,具体干活仍交回 Mika 现有的 Run。

## 领域模型

代码在 `core/superagent/`,规则与来源、执行解耦:

| 概念 | 在 Mika 里 | 规则 |
|---|---|---|
| Source | `Source` 端口(定时、通知、中继渠道逐个接入) | 一条事件流;幂等靠 externalId,游标存进状态 |
| Trigger | `Trigger` | 不可变事件;审计与幂等的单位 |
| Thread | `Thread`(key 与对话 threadId 同一口径) | 至多一个 Eval 在跑,期间 Trigger 并入下一批;持有订阅 |
| Eval | `Engine.evaluateDue` + `Router` 端口 | 门禁(代码)先判,再由 Router 一次判完整批;不持状态 |
| Mandate | `Mandate` | 不可变授权记录:Policy 版本、scope、强度、准入依据、发起人;范围更宽要开新 Ticket |
| Ticket | `Ticket` | 只有 Eval 能创建;open → ended,人的消息可恢复(人停的除外);与 Executor 实例无关 |
| Assignment | `Assignment` ↔ TaskCenter 的 Run | Ticket 与一个 Executor 实例的绑定;pending → running → ended |
| Subscription | `Thread.subscriptions` | 匹配 Trigger 直通给订阅 Ticket,不经 Eval、不带授权 |
| Channel | 复用 `core/run/` 的 Outbox 与 Channel | 回复、确认、通知都走既有送达账本 |

对应关系(接装配时用):Mandate.scope → `RunSpec.capabilities`/`readOnly`;人的消息认领 → `RunInbox` 的插话语义;`report.question` 非空 → WaitingUser。

## 两条路径

- **Eval 路径**:来源不认识的消息先进门禁——名单内且点名(或线程上已有 Ticket 的跟进)是 explicit;只点名是 inferred;其余不进。过门禁的整批交 Router(`LlmRouter`,一次调用判完整批,输出 JSON 数组),判决只有 accept / attach / ignore / stop。accept 在 `Auto` 模式下原子地建 Ticket + Mandate + 首个 Assignment + 订阅;`Observe` 只记「本会接受」(先观察再放开);`Off` 拒绝 inferred。
- **订阅直通**:线程上后续的消息(包括机器人/CI)直接送给订阅的 Ticket 当上下文,不问 Router,不加授权。人的消息落在 Ticket 自己线程上就归它处理,已结束的立即恢复;机器人消息照常进 Eval。

Router 不改写请求:Ticket 只登记原始 Trigger 引用(`instruction = trigger:<id>`),Executor 自己读原文(链式召回)。Router 不可用时该批 Trigger 留在队列里下次再试,不丢。

## 执行与恢复

- `SuperExecutor` 端口由装配层实现:把 Ticket 交给 Mika(经 TaskCenter / `ChildRunHost`),scope 映射成能力子集;结果经 `Engine.report` 回来,`outcome=done` 结束 Ticket,`question` 非空表示在等人。
- `Runner` 负责领取 → 开始 → 执行 → 回报;执行异常按 failed 报告,Ticket 保持 open 可重试。
- **看门狗**(`Engine.watchdog`):Assignment 跑超时没报告,记为 interrupted 并派新的;Executor 靠自查(本地状态、既有送达账本)恢复,不盲目重放外部动作。
- **Policy 变更**(`Engine.policyChanged`):开放 Ticket 的 Mandate 按新名单重验,不再成立的撤销;进行中的工作不删,通知交给人的停止去收尾。

## 状态

全部状态(JSON 快照,原子替换写入,同 `FileRunStateStore` 模式):threads、triggers(封顶 500)、tickets、assignments、decisions(审计)、readPositions(游标)、observations(Observe 记录)。进程重启后接着用,不重放事件。

## 尚未接入

- 来源实例:`Source` 端口已定义,定时可桥接现有 `TriggerEngine`;通知、中继渠道(tools/relay)按实例接入。
- 装配与界面:App 侧实现 `SuperExecutor`(映射 `RunSpec`)、Policy 设置页、按「卡在谁那里」排序的 Ticket 面板。
- 多渠道回复:复用 Outbox 后把 Channel 扩到中继渠道。
