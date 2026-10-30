plugins {
    java
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "net.torosaba"
version = "2.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.bluecolored.de/releases")
    maven("https://repo.mikeprimm.com/")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(kotlin("stdlib"))

    compileOnly("io.papermc.paper:paper-api:26.3.build.227-stable")
    compileOnly("de.bluecolored:bluemap-api:2.7.8")
    compileOnly("us.dynmap:DynmapCoreAPI:3.8") { isTransitive = false }
    compileOnly("com.google.code.gson:gson:2.11.0")

    testImplementation("io.papermc.paper:paper-api:26.3.build.227-stable")
    testImplementation("us.dynmap:DynmapCoreAPI:3.8") { isTransitive = false }
    testImplementation("com.google.code.gson:gson:2.11.0")
    // データベースのテストだけ (MySQL 互換のモード)。本番のドライバー (MySQL Connector/J) は plugin.yml の libraries
    testImplementation("com.h2database:h2:2.5.252")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
    systemProperty("marumasign.sampleRegions", System.getProperty("marumasign.sampleRegions") ?: "")
}

tasks.jar {
    archiveBaseName.set("MarumaSignCompanion")
    archiveClassifier.set("thin")
}

// Kotlin の標準ライブラリは JAR に入れる。ほかの Kotlin 製プラグインと衝突しないよう、パッケージを移す
tasks.shadowJar {
    archiveBaseName.set("MarumaSignCompanion")
    archiveClassifier.set("")
    relocate("kotlin", "net.torosaba.marumasign.companion.libs.kotlin")
    relocate("org.jetbrains.annotations", "net.torosaba.marumasign.companion.libs.annotations")
    relocate("org.intellij.lang.annotations", "net.torosaba.marumasign.companion.libs.intellij")
    minimize()
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
