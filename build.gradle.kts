import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.isitannarli"
version = "1.0.0"

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
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath")
        if (localIde.isPresent) local(localIde.get())
        else create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        bundledPlugin("Git4Idea")
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledModule("intellij.platform.vcs.impl")
        bundledModule("intellij.platform.vcs.impl.shared")
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions { jvmDefault = JvmDefaultMode.NO_COMPATIBILITY }
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.bettergit"
        name = "Better Git"
        version = project.version.toString()
        ideaVersion { sinceBuild = "262" }
        vendor { name = "isitannarli" }
    }
    pluginVerification {
        ides {
            val localVerification = providers.gradleProperty("verificationIdePath")
            val localVerificationList = providers.gradleProperty("verificationIdePaths")
            if (localVerificationList.isPresent) localVerificationList.get().split('|').forEach { local(it) }
            else if (localVerification.isPresent) local(localVerification.get())
            else {
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

tasks.wrapper { gradleVersion = "9.7.1"; distributionType = Wrapper.DistributionType.BIN }
