// Section JetBrains plugin — root project settings.
//
// Single-module Gradle build. The plugin is published as a single .zip
// suitable for the JetBrains Marketplace and for sideload via
// Settings → Plugins → ⚙ → Install Plugin from Disk…

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// NOTE: no `dependencyResolutionManagement { repositoriesMode = ... }` block.
// The gradle-intellij-plugin (1.17.x) resolves the IntelliJ Platform by adding
// repositories at *project* level from inside the plugin — including the
// 'maven2' repo behind ':prepareSandbox'. Under
// RepositoriesMode.FAIL_ON_PROJECT_REPOS that injection fails the build with
// "repository 'maven2' was added by unknown code", which no amount of
// declaring the same repositories centrally can satisfy. Repositories are
// therefore declared in build.gradle.kts, as the JetBrains plugin template
// does for the 1.x plugin.

rootProject.name = "section-jetbrains"
