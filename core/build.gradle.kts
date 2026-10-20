plugins { kotlin("jvm") }

repositories { mavenCentral() }

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("com.google.code.gson:gson:2.14.0")
    constraints {
        implementation("com.google.errorprone:error_prone_annotations:2.50.0")
    }
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
    }
}
tasks.test { useJUnitPlatform() }
