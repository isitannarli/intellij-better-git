plugins { kotlin("jvm") }

repositories { mavenCentral() }

dependencies {
    compileOnly(kotlin("stdlib"))
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(kotlin("stdlib"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(25) }
tasks.test { useJUnitPlatform() }
