// Section JetBrains plugin — module build script.
//
// Uses the gradle-intellij-plugin (legacy, stable on 232–252 range) so we
// can run against the broad IntelliJ Platform range declared in
// gradle.properties. The newer `org.jetbrains.intellij.platform` plugin
// is preferred on 233+ but its 232 compatibility is partial; the legacy
// `org.jetbrains.intellij` plugin (1.17.x) covers the full range.

import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.date
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.4"
    id("org.jetbrains.changelog") version "2.2.1"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    kotlin("plugin.serialization") version "1.9.24"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Project-level repositories: required by the gradle-intellij-plugin, which
// adds its own IntelliJ Platform repositories here at configuration time.
// See the note in settings.gradle.kts about RepositoriesMode.
repositories {
    mavenCentral()
}

dependencies {
    // OkHttp is bundled with the IntelliJ Platform (used internally for
    // updates and Marketplace traffic). Declaring it as a compileOnly
    // dependency keeps the plugin distribution lean — the platform
    // provides it at runtime. Bumping with platform is fine.
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("com.squareup.okio:okio:3.6.0")

    // Kotlinx serialization is included with platform >=232 but pin to
    // a recent version for tests that build outside the sandbox.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3") {
        // Avoid pulling in stdlib — platform owns it.
        exclude(group = "org.jetbrains.kotlin")
    }

    // Test dependencies — plain JUnit 5 + MockWebServer.
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.assertj:assertj-core:3.25.3")
}

// Configure gradle-intellij-plugin.
intellij {
    pluginName = providers.gradleProperty("pluginName")
    version = providers.gradleProperty("platformVersion")
    type = providers.gradleProperty("platformType")
    plugins = providers.gradleProperty("platformPlugins").map { p ->
        p.split(',').map(String::trim).filter(String::isNotEmpty)
    }
    updateSinceUntilBuild = false
}

changelog {
    version = providers.gradleProperty("pluginVersion")
    path = "${project.projectDir}/CHANGELOG.md"
    header = provider { "[${version.get()}] - ${date()}" }
    headerParserRegex = """\d+\.\d+\.\d+""".toRegex()
    itemPrefix = "-"
    keepUnreleasedSection = true
    unreleasedTerm = "[Unreleased]"
    groups = listOf("Added", "Changed", "Fixed", "Removed")
    repositoryUrl = "https://github.com/cwellbournewood/section"
}

ktlint {
    version = "1.2.1"
    verbose = true
    android = false
    outputToConsole = true
    ignoreFailures = false
    filter {
        exclude("**/generated/**")
        include("**/kotlin/**")
    }
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf(
            "-Xjsr305=strict",
            "-opt-in=kotlin.RequiresOptIn",
        )
        jvmTarget = providers.gradleProperty("javaVersion").get()
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
        distributionType = Wrapper.DistributionType.BIN
    }

    patchPluginXml {
        version = providers.gradleProperty("pluginVersion")
        sinceBuild = providers.gradleProperty("pluginSinceBuild")
        untilBuild = providers.gradleProperty("pluginUntilBuild")

        // Pull the latest entry from CHANGELOG.md into the marketplace
        // description so the plugin page shows release notes.
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(providers.gradleProperty("pluginVersion").get()) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }

    // Heavy IntelliJ Platform test harness is opt-in — unit tests use
    // plain JUnit. Skip the platform tests by default unless explicitly
    // requested via `-PrunPlatformTests=true`.
    val runPlatformTests = providers.gradleProperty("runPlatformTests").orNull == "true"
    if (!runPlatformTests) {
        named("buildSearchableOptions") { enabled = false }
        named("runPluginVerifier") { enabled = false }
    }

    buildPlugin {
        // Resulting artifact: build/distributions/section-jetbrains-<ver>.zip
        archiveBaseName.set(providers.gradleProperty("pluginName").get())
        archiveVersion.set(providers.gradleProperty("pluginVersion").get())
    }

    publishPlugin {
        // Token comes from JETBRAINS_MARKETPLACE_TOKEN env var.
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
        // Stable channel by default; CI can override with -Pchannel=eap.
        channels = providers.gradleProperty("channel").orElse("default").map { listOf(it) }
    }
}
