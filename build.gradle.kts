// The plugins {} block runs before the Project object exists, so gradle.properties
// values (normally read via property()) are not reachable here. The Loom version is
// hardcoded to match the loom_version value in gradle.properties.
plugins {
    id("fabric-loom") version "1.17.20"
}

version = property("version") as String
group = property("group") as String

repositories {
    // Loom adds the essential Fabric/Mojang maven repositories automatically.
    // Add third-party mod repositories here if a dependency ever needs one.
    // mavenCentral() is here only for the headless test toolchain (JUnit 5);
    // the mod itself ships with no third-party runtime dependency.
    mavenCentral()
}

// This spike is client-only and small enough that splitEnvironmentSourceSets()
// is not needed: everything lives in src/main/java and fabric.mod.json marks
// "environment": "client" so the mod is rejected on a dedicated server.

dependencies {
    // Minecraft 26.2 ships unobfuscated: no Yarn mappings are published for it
    // (https://meta.fabricmc.net/v2/versions/yarn/26.2 returns an empty list) and
    // Loom does not require a mappings() entry for this version. Because nothing
    // needs remapping, plain `implementation` is used instead of `modImplementation`.
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // JUnit 5 (Jupiter). The app.seedscout.nav.protocol package has ZERO Minecraft
    // imports by design, so its whole test suite runs headlessly under `gradlew test`
    // with no game, no window and no Loom run task.
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.processResources {
    val projectVersion = project.version
    inputs.property("version", projectVersion)

    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to projectVersion))
    }
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.name}" }
    }
}
