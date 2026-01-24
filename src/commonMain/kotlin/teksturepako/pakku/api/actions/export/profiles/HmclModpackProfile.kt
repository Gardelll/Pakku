package teksturepako.pakku.api.actions.export.profiles

import teksturepako.pakku.api.actions.export.exportProfile
import teksturepako.pakku.api.actions.export.rules.hmclModpackRule
import teksturepako.pakku.api.actions.export.rules.replacementRule

fun hmclModpackProfile() = exportProfile(name = "hmclmodpack") {
    rule { hmclModpackRule() }
    rule { replacementRule() }
}
