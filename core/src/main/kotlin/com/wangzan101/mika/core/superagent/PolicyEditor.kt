package com.wangzan101.mika.core.superagent

/** 界面编辑的最小纯模型;保存时生成新的 Policy,旧 Mandate 只有在下一次重验才受影响。 */
object PolicyEditor {
    fun addActor(policy: Policy, instance: String, actor: String): Policy {
        val clean = actor.trim()
        if (clean.isEmpty()) return policy
        val current = policy.allowedActors[instance].orEmpty()
        if (clean in current) return policy
        return policy.copy(
            version = bump(policy.version),
            allowedActors = policy.allowedActors + (instance to (current + clean)),
        )
    }

    fun removeActor(policy: Policy, instance: String, actor: String): Policy {
        val current = policy.allowedActors[instance].orEmpty()
        if (actor !in current) return policy
        return policy.copy(
            version = bump(policy.version),
            allowedActors = policy.allowedActors + (instance to (current - actor)),
        )
    }

    fun setAcceptMode(policy: Policy, mode: AcceptMode): Policy =
        if (policy.acceptMode == mode) policy else policy.copy(version = bump(policy.version), acceptMode = mode)

    fun enableRule(policy: Policy, instance: String, rule: String): Policy {
        val clean = rule.trim()
        if (clean.isEmpty()) return policy
        val current = policy.enabledRules[instance].orEmpty()
        if (clean in current) return policy
        return policy.copy(
            version = bump(policy.version),
            enabledRules = policy.enabledRules + (instance to (current + clean)),
        )
    }

    fun setCell(
        policy: Policy,
        instance: String,
        scope: String = "*",
        identity: Identity,
        targeting: Targeting,
        cell: AdmissionCell,
    ): Policy {
        val sourceTables = policy.admissionTables[instance].orEmpty()
        val table = sourceTables[scope] ?: sourceTables["*"] ?: AdmissionTable.DEFAULT
        val next = when (identity) {
            Identity.Allowed -> when (targeting) {
                Targeting.Address -> table.copy(allowedAddressed = cell)
                Targeting.Followup -> table.copy(allowedFollowup = cell)
                Targeting.Unaddressed -> table.copy(allowedUnaddressed = cell)
            }
            Identity.Unlisted -> when (targeting) {
                Targeting.Address -> table.copy(unlistedAddressed = cell)
                Targeting.Followup -> table.copy(unlistedFollowup = cell)
                Targeting.Unaddressed -> table.copy(unlistedUnaddressed = cell)
            }
            Identity.Unknown -> when (targeting) {
                Targeting.Address -> table.copy(unknownAddressed = cell)
                Targeting.Followup -> table.copy(unknownFollowup = cell)
                Targeting.Unaddressed -> table.copy(unknownUnaddressed = cell)
            }
        }
        return policy.copy(
            version = bump(policy.version),
            admissionTables = policy.admissionTables + (instance to (sourceTables + (scope to next))),
        )
    }

    private fun bump(version: String): String {
        val base = version.substringBeforeLast('#')
        val current = version.substringAfterLast('#', "0").toIntOrNull() ?: 0
        return "$base#${current + 1}"
    }
}
