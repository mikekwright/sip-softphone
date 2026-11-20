plugins {
    application
}

description = "Terminal (audio only) SIP softphone built on softphone-lib"

dependencies {
    implementation(project(":softphone-lib"))
    implementation(libs.jline)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.github.mikekwright.sip.softphone.terminal.TerminalSoftphone")
    applicationName = "terminal-softphone"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
