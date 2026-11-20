rootProject.name = "sip-softphone"

include("softphone-lib")
include("clients:terminal-softphone")
include("clients:web-softphone")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
