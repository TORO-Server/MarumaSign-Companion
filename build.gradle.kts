plugins {
    java
}

group = "net.torosaba"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.bluecolored.de/releases")
    maven("https://repo.mikeprimm.com/")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.227-stable")
    compileOnly("de.bluecolored:bluemap-api:2.7.8")
    compileOnly("us.dynmap:DynmapCoreAPI:3.8") { isTransitive = false }
    compileOnly("com.google.code.gson:gson:2.11.0")

    testImplementation("io.papermc.paper:paper-api:26.3.build.227-stable")
    testImplementation("us.dynmap:DynmapCoreAPI:3.8") { isTransitive = false }
    testImplementation("com.google.code.gson:gson:2.11.0")
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
    systemProperty("signmap.sampleRegions", System.getProperty("signmap.sampleRegions") ?: "")
}

tasks.jar {
    archiveBaseName.set("BlueMapMarumaSign")
}

