# Super-agent 开发计划

定位与边界见 [position.md](position.md),领域模型见 [super-agent.md](super-agent.md)。本文是执行清单:每项任务做完勾掉,验收标准不达不勾。

原则:依赖顺序自上而下;领域层(Phase 1)不碰 App;每个任务带测试,域层测试:源码 ≥ 1:1;上游同步照 [position.md](position.md) 的固定动作。

## 现状(已完成)

- [x] 领域骨架:`core/superagent/`(Domain/Engine/Router/Ports/Runner/Store),20 个测试
- [x] 定位与架构文档、上游同步流程(已验证 v0.1.34 合并)
- [x] autonomy 包(冻结待并入,见 2.5)

## Phase 1 领域规则补齐(纯 core,不碰 App)

| # | 任务 | 做什么 | 验收 |
|---|---|---|---|
| 1.1 | 处置账本 | ✅ Trigger 落库同时记 Disposition:fanout、context、handbacks、acked、requeued、evalId、decisionId;Engine 是唯一写者 | 每条规则(拥有权/恢复/未读重评)都能从账本算出;幂等与过滤有审计记录 |
| 1.2 | followupOwners 谓词 | ✅ 人的跟进归谁:自己线程上的票拥有;退回的不管;只读上下文不管;已结束未停止且消息未送达的仍拥有(恢复);重排队/评估持有中的不归 | 谓词纯函数全分支用例;Engine.receive 按它决定入队与否 |
| 1.3 | 报告状态机 | ✅ Assignment 报告六态(progress/waiting/ready/blocked/failed/dropped)+ attention 问题;`waitsForPerson` 可计算谓词;done/merged/closed/dropped/stopped 结局 | 「在等人」从字段算出,不从文本猜;报告驱动的票结束规则齐 |
| 1.4 | handback 与 ack | ✅ 工人退回超范围消息(记账,无人拥有时回归 Eval);ack 认领消息;票结束时未读跟进释放 | 退回消息最终有新判决;已 ack 的不重评 |
| 1.5 | Eval 生命周期 | ✅ 候选送达起算 deadline;暂停顺移;超时重排 2 次后 unavailable(面板可见、可 dismiss);Router 不可用不烧重试次数 | 每种超时/暂停/恢复路径有测试;unavailable 有记录 |
| 1.6 | 批内选主与去重 | ✅ 一批取最强(显式>推断,同强取最新)做 Mandate 种子;已开票的 Trigger 判 startedTicket 忽略;followupOwners 命中的先出队 | 同消息双开票不可能;种子字段正确 |
| 1.7 | 机器门 | bot/CI 单独门线:名单 bot 至多 inferred;无名单 bot 拒;无发送者事件按准入表;`human` 判定来自 Source | 每类机器事件有正反用例 |
| 1.8 | 两套判决 | 显式请求走轻量 route(现有 orchestrator_route 形态);推断候选走严格评估:逐规则判定+方向+动机引用+宿主校验 | 推断接受缺任一要素被宿主拒绝;两路径测试分离 |
| 1.9 | 准入表 | 每来源每范围 3×3 格子(名单内外×是否点名),格值 ignore/inferred/explicit;显式格子优先于 followup | 格子配置驱动门禁;默认值与 DSH 一致 |
| 1.10 | Mandate 重验 | 按 basis 分路径重验:来源还在听、bot 还在名单、格子强度不降、规则仍启用、模式仍 auto;policyEffects 计算受影响票 | 弱化不立即生效,重验时才撤;每 basis 有用例 |
| 1.11 | 回放评测基建 | 录一次真实模型轨迹(门禁→Router→判决),JSONL 存样;CI 用脚本模型断网重放,输出必须逐字节一致 | 领域层改动破坏回放即测试红;样例含显式/推断/机器三类 |

## Phase 2 执行接入(App 侧适配)

| # | 任务 | 做什么 | 验收 |
|---|---|---|---|
| 2.1 | SuperExecutor 适配器 | Ticket → RunSpec:scope 映射能力子集与 readOnly;经 TaskCenter 派工;六态报告回流 Engine | 真机跑通一张票全生命周期 |
| 2.2 | 效果队列 | Runner 改造成持久队列:每副作用一行,退避重试,保序,Later 语义,送达凭据 | 杀进程后待执行副作用不丢不重 |
| 2.3 | 看门狗安卓化 | Assignment 超时→interrupted→重派;桥接 RunRecovery 的自查恢复;前台服务保活策略 | 模拟进程死亡,票跨死亡续跑 |
| 2.4 | Router 装配 | 真模型接 LlmRouter;提示词按 dispatch 纪律(转引用不转述、附注≤30词、批量判);token 记账 | 每次判决计入用量;批内一次调用 |
| 2.5 | autonomy 并入 | Goal 长期目标改造成例程票(routine ticket),删除 core/autonomy,迁移其测试 | 两套状态机归一;文档同步 |

## Phase 3 渠道(来源接入)

| # | 任务 | 做什么 | 验收 |
|---|---|---|---|
| 3.1 | relay 渠道框架 | relay 侧连接器接口:收消息→Trigger 帧推手机;回复帧发回;断线缓冲 | 手机离线,消息不丢 |
| 3.2 | Telegram | 长轮询 bot:mention/private 判定、allow_from 名单、group_policy | @ 即答(快速路径)与开工单(任务)双路可用 |
| 3.3 | 飞书 | 长连接 bot(lark-oapi 同款机制);群/私聊判定 | 同上 |
| 3.4 | 本机通知来源 | Android 通知监听→Trigger;按包名配置听谁 | 新来源接入不改 core |
| 3.5 | 回复通道 | Outbox 扩展:回复按来源原路返回;deliver-here 标记的聊天收主动推送 | 结果与提问走同一会话 |

## Phase 4 策略与界面

| # | 任务 | 做什么 | 验收 |
|---|---|---|---|
| 4.1 | 策略设置 | 每来源名单/准入表/accept 模式(Off/Observe/Auto)的设置页;策略版本号随改随变 | 改配置即改门禁;版本进 Mandate |
| 4.2 | Ticket 面板 | 阶段表排序(person/* 沉顶);票详情(输入、报告、授权、账本) | 面板只顶出需要人的票 |
| 4.3 | 通知 | person 阶段票发系统通知;点了直达面板 | 通知可关,不扰 |

## Phase 5 观察,然后放开

| # | 任务 | 做什么 | 验收 |
|---|---|---|---|
| 5.1 | Observe 审计 | 「本会接受」日志页:时间、来源、触发、规则、标题 | 人工可审,可一键转正规则 |
| 5.2 | 逐来源 Auto | 按来源逐个放开;准入格逐格放宽 | 每次放开有审计对照 |
| 5.3 | 人格与记忆 | Worker 提示词接 Mika 记忆/知识;分身人格设定 | 简报/回复有稳定人设 |

## 里程碑

| 里程碑 | 内容 | 判定 |
|---|---|---|
| M1 领域完备 | Phase 1 全勾 | 回放评测绿;测试:源码 ≥ 1:1 |
| M2 手机开机 | Phase 2 全勾 | 真机:一张票从触发到回报,杀进程续跑 |
| M3 有耳朵了 | Phase 3.1-3.2 | Telegram @ 它,群里有回音 |
| M4 分身上线 | Phase 4 + 5.2 | 自己用一周,Observe 日志干净 |
