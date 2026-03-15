plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    id("org.springframework.boot") version "4.0.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.chatsummary"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-aop:3.4.3")
    implementation("org.springframework.retry:spring-retry:2.0.11")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Telegram Bot
    implementation("org.telegram:telegrambots-springboot-longpolling-starter:7.11.0")
    implementation("org.telegram:telegrambots-client:7.11.0")

    // Google Gemini API
    implementation("com.google.genai:google-genai:1.1.0")

    // Logging to Grafana Loki
    implementation("com.github.loki4j:loki-logback-appender:1.6.0")
    implementation("org.codehaus.janino:janino:3.1.12")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<Test> {
    useJUnitPlatform()
}
