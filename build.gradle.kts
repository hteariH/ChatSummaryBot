plugins {
    id("org.springframework.boot") version "4.0.3"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "com.chatsummary"
version = "1.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-aspectj:4.0.3")
    implementation("org.springframework.retry:spring-retry:2.0.11")

    // Telegram Bot
    implementation("org.telegram:telegrambots-springboot-longpolling-starter:9.6.0")
    implementation("org.telegram:telegrambots-client:9.6.0")

    // Google Gemini API
    implementation("com.google.genai:google-genai:1.1.0")

    // Logging to Grafana Loki
    implementation("com.github.loki4j:loki-logback-appender:1.6.0")
    implementation("org.codehaus.janino:janino:3.1.12")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<Test> {
    useJUnitPlatform()
}
