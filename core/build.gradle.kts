plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * Platform-independent Poet code, shared by the Android app (:app) and the
 * Linux desktop build (:desktop) — see docs/native-ui-solidification.md §5.
 *
 * Nothing in here may import android.* or androidx.*. The rule is enforced by
 * the module boundary: this is a plain Kotlin/JVM library with no Android
 * dependency on its compile classpath, so an accidental Android import fails
 * the build rather than silently binding the desktop port to the phone.
 */
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // The routes and the view layer live here now (docs/desktop-app-plan.md
    // §2.3), so :core carries Ktor. Both builds embed the same routing table.
    api(libs.ktor.server.core)
    testImplementation(libs.junit)
}
