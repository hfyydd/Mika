package com.wangzan101.mika.core.superagent

/** Observe 模式审计页;一行只登记「本会接受」的引用,不做自动放行。 */
object ObservationLog {
    data class Row(val triggerId: String, val title: String)

    fun rows(state: SuperagentState): List<Row> = state.observations.asReversed().mapNotNull { entry ->
        Regex("^would accept ([^:]+): (.*)$").find(entry)?.destructured?.let { (triggerId, title) ->
            Row(triggerId, title)
        }
    }

    /** 「转正」只把规则加入 enabledRules;它仍要等 Router 给 matchedRule、方向、动机引用和宿主校验。 */
    fun promote(policy: Policy, state: SuperagentState, triggerId: String): Policy {
        // Missing audit references are intentionally ignored, never promoted by id collision.
        val trigger = state.triggers.firstOrNull { it.id == triggerId } ?: return policy
        return PolicyEditor.enableRule(policy, trigger.source.instance, ruleId(trigger))
    }

    fun ruleId(trigger: Trigger) = "${trigger.source.kind}:${trigger.source.instance}:addressed"
}
