plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

javafx {
    version = "25.0.4"
    modules = listOf("javafx.controls", "javafx.web")
}

application {
    mainClass = "org.example.Main"
    applicationName = "java-aip-usage"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.18.2"))
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
