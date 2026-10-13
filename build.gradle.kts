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
    // The second option lets the program name the toolkit's application, which is what macOS shows in the menu beside the
    // apple ("Quit aip usage"): the toolkit has no public way to do it, and here it is on the module path, where its own
    // classes are closed to the program unless the start opens them. See MacName.
    val toolkit = listOf(
        "--enable-native-access=javafx.graphics,javafx.web",
        "--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED",
    )
    // On macOS the start also names the program and its icon for the Dock. Only the launcher of macOS knows these two
    // options; on another system they would stop the start. The toolkit follows the icon; whether anything follows the
    // name is not known, and it does no harm.
    val dock = if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        listOf("-Xdock:name=aip usage", "-Xdock:icon=" + file("src/main/resources/icons/icon-256.png").absolutePath)
    } else {
        emptyList()
    }
    applicationDefaultJvmArgs = toolkit + dock
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Lets a test that opens real windows be asked for: ./gradlew test -Daipusage.windows=true
    System.getProperty("aipusage.windows")?.let { systemProperty("aipusage.windows", it) }
}

// The frontend's logic and page script, run under Node. Kept out of `build` so
// that building needs nothing but a JDK; run it with `./gradlew frontendTest`.
tasks.register<Exec>("frontendTest") {
    group = "verification"
    description = "Runs the frontend tests with Node"
    // The test files by name: a directory is no longer taken for the tests in it by newer versions of Node.
    commandLine(listOf("node", "--test") + fileTree("src/test/frontend").matching { include("*.test.cjs") }.files.map { it.path }.sorted())
}
