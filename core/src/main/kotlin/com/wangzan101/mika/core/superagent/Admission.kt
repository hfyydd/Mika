package com.wangzan101.mika.core.superagent

import kotlinx.serialization.Serializable

@Serializable
enum class AdmissionCell { Ignore, Inferred, Explicit }

enum class Identity { Allowed, Unlisted, Unknown }

enum class Targeting { Address, Followup, Unaddressed }

/** 来源+范围可覆盖的 3×3 准入表;机器门在其上保持 inferred 硬上限。 */
@Serializable
data class AdmissionTable(
    val allowedAddressed: AdmissionCell = AdmissionCell.Explicit,
    val allowedFollowup: AdmissionCell = AdmissionCell.Explicit,
    val allowedUnaddressed: AdmissionCell = AdmissionCell.Inferred,
    val unlistedAddressed: AdmissionCell = AdmissionCell.Inferred,
    val unlistedFollowup: AdmissionCell = AdmissionCell.Ignore,
    val unlistedUnaddressed: AdmissionCell = AdmissionCell.Ignore,
    val unknownAddressed: AdmissionCell = AdmissionCell.Inferred,
    val unknownFollowup: AdmissionCell = AdmissionCell.Ignore,
    val unknownUnaddressed: AdmissionCell = AdmissionCell.Ignore,
) {
    fun cell(identity: Identity, targeting: Targeting): AdmissionCell = when (identity) {
        Identity.Allowed -> when (targeting) {
            Targeting.Address -> allowedAddressed
            Targeting.Followup -> allowedFollowup
            Targeting.Unaddressed -> allowedUnaddressed
        }
        Identity.Unlisted -> when (targeting) {
            Targeting.Address -> unlistedAddressed
            Targeting.Followup -> unlistedFollowup
            Targeting.Unaddressed -> unlistedUnaddressed
        }
        Identity.Unknown -> when (targeting) {
            Targeting.Address -> unknownAddressed
            Targeting.Followup -> unknownFollowup
            Targeting.Unaddressed -> unknownUnaddressed
        }
    }

    companion object { val DEFAULT = AdmissionTable() }
}

fun Policy.admissionTable(sourceInstance: String, scope: String = "*"): AdmissionTable =
    admissionTables[sourceInstance]?.get(scope)
        ?: admissionTables[sourceInstance]?.get("*")
        ?: AdmissionTable.DEFAULT
