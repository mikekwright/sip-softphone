# Tooling and build

| Item | Choice |
|------|--------|
| Language | Java 21 (toolchain; `--release 21`) |
| Build | Gradle 9 wrapper, Kotlin DSL, version catalog `gradle/libs.versions.toml` |
| Networking | Netty 4.2 (NIO transport) |
| Logging | SLF4J API in the library; Logback in clients and tests |
| Tests | JUnit 6 (Jupiter) + AssertJ; Playwright/Chromium for the web E2E script |
| Terminal UI | JLine 3 |
| JSON (web client) | Jackson |
| CI | GitHub Actions `.github/workflows/build.yml` runs `./gradlew build` |

## Common commands

```bash
./gradlew build                                   # compile, test, javadoc, jars for all modules
./gradlew :softphone-lib:test                     # library tests (~190 tests, ~15 s)
./gradlew :softphone-lib:publishToMavenLocal      # install the jar (+ sources, javadoc) locally
./gradlew :softphone-lib:publishMavenPublicationToStagingRepository   # staged release in build/staging-repo
./gradlew :clients:terminal-softphone:installDist # terminal client launcher in build/install
./gradlew :clients:web-softphone:run              # web client on http://localhost:8080
```

## Publishing

`softphone-lib` applies `maven-publish` and `signing`, produces sources and javadoc jars and a complete POM
(license, SCM, developer). Signing is enabled when `-PsigningKey` / `-PsigningPassword` are provided. Upload the
staged repository (or add a repository with credentials) to publish to Maven Central.

## Testing conventions

* Network tests bind to `127.0.0.1` with port 0 so they can run in parallel and in CI.
* Timeout paths use `TimerSettings` with T1 = 10 ms.
* Media assertions use `RecordingSink` + `ToneDetector` rather than sleeps on wall time.
* Fault injection uses `SipMessageInterceptor` (drop / rewrite messages).
