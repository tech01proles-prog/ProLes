import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
    id("io.ktor.plugin") version "3.0.0"
    application
}

group = "com.proles.server"
version = "1.0.0"

application {
    mainClass.set("com.proles.server.ApplicationKt")
}

ktor {
    docker {
        jreVersion.set(JavaVersion.VERSION_21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core-jvm:3.0.0")
    implementation("io.ktor:ktor-server-netty-jvm:3.0.0")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.0.0")

    // Serialization
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Database (Exposed + PostgreSQL)
    implementation("org.jetbrains.exposed:exposed-core:0.56.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.56.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.56.0")
    implementation("org.postgresql:postgresql:42.7.3")

    // JWT & Security
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("at.favre.lib:bcrypt:0.10.2")

    // ✅ Добавьте логгер (если ещё нет):
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // 🔥 Firebase Admin для отправки пушей
    implementation("com.google.firebase:firebase-admin:9.3.0")

    // 📧 Email (Simple Java Mail + Jakarta Mail runtime)
    implementation("org.simplejavamail:simple-java-mail:8.10.0")
    implementation("org.eclipse.angus:angus-mail:2.0.3")
    implementation("jakarta.activation:jakarta.activation-api:2.1.3")
    implementation("org.eclipse.angus:angus-activation:2.0.2")

    implementation("io.ktor:ktor-server-html-builder-jvm:3.0.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
