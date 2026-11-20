plugins {
    `java-library`
    `maven-publish`
    signing
}

description = "SIP softphone library: Netty based SIP (RFC 3261), SDP (RFC 8866/3264) and RTP (RFC 3550) stack"

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api(platform(libs.netty.bom))
    api(libs.netty.buffer)
    api(libs.netty.transport)
    implementation(libs.netty.codec)
    implementation(libs.netty.handler)
    api(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "softphone-lib",
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "com.github.mikekwright.sip.softphone"
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "softphone-lib"
            from(components["java"])
            pom {
                name.set("SIP Softphone Library")
                description.set(project.description)
                url.set("https://github.com/mikekwright/sip-softphone")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("mikekwright")
                        name.set("Mike Wright")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/mikekwright/sip-softphone.git")
                    developerConnection.set("scm:git:ssh://git@github.com/mikekwright/sip-softphone.git")
                    url.set("https://github.com/mikekwright/sip-softphone")
                }
            }
        }
    }
    repositories {
        // Local staging repository; publish to Maven Central by uploading the staged bundle
        // or by adding a repository block with credentials supplied via gradle.properties.
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-repo"))
        }
    }
}

signing {
    // Only sign when keys are configured (e.g. in CI): -PsigningKey=... -PsigningPassword=...
    val signingKey = findProperty("signingKey") as String?
    val signingPassword = findProperty("signingPassword") as String?
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}
