plugins {
    alias(libs.plugins.kotlin.jvm)
}

// No Android dependency by design. The PRD specifies a C++ core; this is the
// Kotlin stand-in (see build-env notes). Keep inner loops on primitive arrays
// with no allocation so the eventual port stays mechanical.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
