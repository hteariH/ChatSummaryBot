plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.allopen") version "2.2.0"
    id("io.quarkus")
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

group = "com.chatsummary"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Quarkus BOM
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))

    // Quarkus core
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-arc")

    // MongoDB Panache (imperative)
    implementation("io.quarkus:quarkus-mongodb-panache-kotlin")

    // Scheduler (Quartz-based)
    implementation("io.quarkus:quarkus-scheduler")

    // Config
    implementation("io.quarkus:quarkus-config-yaml")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Telegram Bot (framework-agnostic)
    implementation("org.telegram:telegrambots-longpolling:7.11.0")
    implementation("org.telegram:telegrambots-client:7.11.0")

    // Google Gemini API
    implementation("com.google.genai:google-genai:1.1.0")

    // Cron expression parsing
    implementation("com.cronutils:cron-utils:9.2.1")

    // Logging to Grafana Loki
    implementation("com.github.loki4j:loki-logback-appender:1.6.0")
    implementation("org.codehaus.janino:janino:3.1.12")

    // Test
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

allOpen {
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.enterprise.context.RequestScoped")
    annotation("jakarta.ws.rs.Path")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
