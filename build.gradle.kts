plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.power.assert) apply false
    alias(libs.plugins.dokka) apply false
}

group = "teksturepako.pakku"
version = "1.3.5-hmcl"

tasks.register("printVersion") {
    notCompatibleWithConfigurationCache(/* reason = */ "Prints version to stdout for CI")
    doLast {
        println(version)
    }
}
