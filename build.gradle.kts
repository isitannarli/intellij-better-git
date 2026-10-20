import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

buildscript {
    dependencies {
        // Keep build-only libraries current without adding them to the plugin distribution.
        classpath(platform("com.fasterxml.jackson:jackson-bom:2.22.4"))
        classpath(platform("io.smallrye.common:smallrye-common-bom:2.21.1"))
        classpath(platform("org.jetbrains.kotlinx:kotlinx-serialization-bom:1.12.0"))
        constraints {
            listOf(
                "org.jetbrains.intellij.plugins:structure-base:3.335",
                "org.jetbrains.intellij.plugins:structure-ide:3.335",
                "org.jetbrains.intellij.plugins:structure-intellij:3.335",
                "org.jetbrains.intellij:blockmap:1.0.10",
                "org.jsoup:jsoup:1.23.2",
                "org.atteo:evo-inflector:2.0",
                "io.undertow:undertow-core:2.4.4.Final",
                "com.google.code.gson:gson:2.14.0",
                "com.google.errorprone:error_prone_annotations:2.50.0",
                "com.github.ben-manes.caffeine:caffeine:3.3.1",
                "com.squareup.okhttp3:okhttp:5.5.0",
                "com.squareup.okio:okio:3.18.2",
                "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0",
                "commons-codec:commons-codec:1.23.0",
                "commons-io:commons-io:2.22.0",
                "org.apache.commons:commons-lang3:3.21.0",
                "org.tukaani:xz:1.12",
                "com.fasterxml.woodstox:woodstox-core:7.3.0",
                "org.codehaus.woodstox:stax2-api:4.3.2",
                "org.glassfish.jaxb:jaxb-runtime:4.0.9",
                "org.eclipse.angus:angus-activation:2.0.3",
                "com.sun.istack:istack-commons-runtime:4.2.0",
                // The Marketplace REST client still uses javax.xml.bind, not jakarta.xml.bind.
                "com.sun.xml.bind:jaxb-impl:2.3.9",
                "org.jboss.logging:jboss-logging:3.6.3.Final",
                "org.jboss.threads:jboss-threads:3.11.1",
                "org.jboss.xnio:xnio-api:3.8.17.Final",
                "org.jboss.xnio:xnio-nio:3.8.17.Final",
                "org.jspecify:jspecify:1.0.1",
            ).forEach { add("classpath", it) }
        }
    }
}

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.isitannarli"
version = "1.1.0"

allprojects {
    tasks.withType<Jar>().configureEach {
        from(rootProject.layout.projectDirectory.file("LICENSE")) {
            into("META-INF")
        }
    }
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    // The IDE owns test runtime libraries; align the separately resolved compile artifacts.
    testCompileOnly(platform("com.fasterxml.jackson:jackson-bom:2.22.4"))
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath")
        if (localIde.isPresent) local(localIde.get())
        else create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        bundledPlugin("Git4Idea")
        // The first published 243 test/instrumentation libraries follow the initial WebStorm build.
        testFramework(TestFrameworkType.Platform, "243.21565.193")
        javaCompiler("243.21565.193")
        pluginVerifier("1.411")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Keep generated code compatible with the Kotlin runtime bundled in the oldest supported IDE.
        languageVersion = KotlinVersion.KOTLIN_2_0
        apiVersion = KotlinVersion.KOTLIN_2_0
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.bettergit"
        name = "Better Git"
        version = project.version.toString()
        ideaVersion { sinceBuild = "243" }
        vendor { name = "isitannarli" }
    }
    pluginVerification {
        ides {
            val localVerification = providers.gradleProperty("verificationIdePath")
            val localVerificationList = providers.gradleProperty("verificationIdePaths")
            val requestedIdes = providers.gradleProperty("verificationIdes")
            if (localVerificationList.isPresent) localVerificationList.get().split('|').forEach { local(it) }
            else if (localVerification.isPresent) local(localVerification.get())
            else if (requestedIdes.isPresent) create(requestedIdes.map { it.split(',').map(String::trim) })
            else {
                listOf("2024.3", "2025.1", "2025.2", "2025.3", "2026.1").forEach {
                    create(IntelliJPlatformType.WebStorm, it)
                }
                create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
                create(IntelliJPlatformType.WebStorm, "2026.2.3")
                create(IntelliJPlatformType.GoLand, "2026.2.3")
                create(IntelliJPlatformType.Rider, "2026.2.3.1")
            }
        }
    }
}

tasks.buildPlugin {
    from(layout.projectDirectory.file("LICENSE"))
}

tasks.test {
    useJUnit()
    maxHeapSize = "2g"
}

tasks.wrapper { gradleVersion = "9.8.2"; distributionType = Wrapper.DistributionType.BIN }
