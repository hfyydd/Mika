package com.wangzan101.mika.core.superagent

/** Policy 重验的受影响票;弱化不自动生效,只有 policyChanged 调用才撤票。 */
data class PolicyEffect(val ticketId: String, val reason: String)
