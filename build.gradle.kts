subprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")

    group = "dev.queryguard"
    version = "0.9.0"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(rootProject.libs.assertj)
        "testImplementation"(rootProject.libs.mockito)
        "testImplementation"(rootProject.libs.mockito.junit)
        "testRuntimeOnly"(rootProject.libs.junit.launcher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing", "-Xlint:-deprecation"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            // Integration tests need Docker; CI and `./gradlew integrationTest` run them.
            excludeTags("integration")
        }
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        finalizedBy("jacocoTestReport")
    }

    tasks.register<Test>("integrationTest") {
        description = "Runs tests that start real containers."
        group = "verification"
        val test = tasks.named<Test>("test").get()
        testClassesDirs = test.testClassesDirs
        classpath = test.classpath
        useJUnitPlatform { includeTags("integration") }
        testLogging {
            events("passed", "failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}
