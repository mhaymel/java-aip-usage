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
    // JavaFX loads native libraries; without this the JDK warns on every start
    // that restricted methods "will be blocked in a future release".
    applicationDefaultJvmArgs = listOf("--enable-native-access=javafx.graphics,javafx.web")
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

// The frontend's logic and page script, run under Node. Kept out of `build` so
// that building needs nothing but a JDK; run it with `./gradlew frontendTest`.
tasks.register<Exec>("frontendTest") {
    group = "verification"
    description = "Runs the frontend tests with Node"
    commandLine("node", "--test", "src/test/frontend")
}
