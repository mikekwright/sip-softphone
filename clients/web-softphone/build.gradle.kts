plugins {
    application
}

description = "Web based (audio + video) SIP softphone built on softphone-lib"

dependencies {
    implementation(project(":softphone-lib"))
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.codec.http)
    implementation(libs.netty.handler)
    implementation(libs.jackson.databind)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.github.mikekwright.sip.softphone.web.WebSoftphone")
    applicationName = "web-softphone"
}
