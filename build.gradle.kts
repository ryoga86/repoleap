import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Optional path to a locally installed IDE (see gradle.properties)
val localIdePath: Provider<String> = providers.gradleProperty("localIdePath")

kotlin {
    // IntelliJ Platform 2025.2 (since-build 252) runs on Java 21
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    // https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }
}

// https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    buildSearchableOptions = providers.gradleProperty("buildSearchableOptions").map { it.toBoolean() }.orElse(true)

    pluginConfiguration {
        id = providers.gradleProperty("pluginId")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        vendor {
            name = providers.gradleProperty("pluginVendor")
            email = providers.gradleProperty("pluginVendorEmail")
            url = providers.gradleProperty("pluginRepositoryUrl")
        }

        // Change notes are taken from CHANGELOG.md
        val changelog = project.changelog
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    // https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
    // CI: environment variables with the PEM contents. Local: file paths + password in ~/.gradle/gradle.properties.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
            .orElse(providers.gradleProperty("repoleap.signing.password"))
        certificateChainFile = layout.file(providers.gradleProperty("repoleap.signing.certificateChainFile").map(::File))
        privateKeyFile = layout.file(providers.gradleProperty("repoleap.signing.privateKeyFile").map(::File))
    }

    // https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse(providers.gradleProperty("repoleap.publishToken"))
        // A pre-release version like 1.2.3-beta.1 is published to the "beta" channel, everything else to "default"
        channels = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            listOf(pluginVersion.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    // https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html
    pluginVerification {
        ides {
            if (localIdePath.isPresent) {
                // Fast local check against the installed IDE (no downloads)
                local(file(localIdePath.get()))
            } else {
                recommended()
            }
        }
    }
}

// https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = "" // tags and GitHub releases are named like the version (1.0.0), see build.yml
}

// Extra run configuration: start the plugin inside a locally installed IDE (e.g. your current IntelliJ IDEA)
if (localIdePath.isPresent) {
    intellijPlatformTesting {
        runIde {
            register("runLocalIde") {
                localPath = file(localIdePath.get())
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "9.5.0"
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

    // verifyPluginSignature checks the output of signPlugin, but doesn't declare the dependency itself
    verifyPluginSignature {
        dependsOn(signPlugin)
    }
}
