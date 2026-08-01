plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * Poet Music — Linux desktop build (docs/native-ui-solidification.md §5).
 *
 * Deliberately a plain Kotlin/JVM module with no Android on the classpath: the
 * shared code comes from :core, and anything the desktop needs that Android
 * used to provide (storage, playback, file picking) is implemented here.
 *
 * `./gradlew :desktop:run`        — start it against the local source tree
 * `./gradlew :desktop:packageDeb` — build the installable .deb
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

sourceSets {
    main {
        // The frontend is the same one the phone serves — served from the jar
        // rather than copied, so the two can never drift.
        resources.srcDir("../app/src/main/assets")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.nop)
    // The native window (GTK 3 + WebKitGTK) and the GStreamer bindings are both
    // JNA over the system libraries — nothing native is bundled in the .deb.
    implementation(libs.jna)
    implementation(libs.gst1.java.core)
    implementation(libs.jaudiotagger)
    testImplementation(libs.junit)
}

val mainClassName = "com.musa.poetmusic.desktop.MainKt"
val appVersion = libs.versions.appVersionName.get()

// lintian rejects @localhost as a bogus mail host. Override for a real
// release with: ./gradlew :desktop:packageDeb -Ppoet.deb.maintainer=you@example.com
val debMaintainer: String = (findProperty("poet.deb.maintainer") as String?)
    ?: "poet-music@noreply.invalid"

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to mainClassName,
            "Implementation-Title" to "Poet Music",
            "Implementation-Version" to appVersion
        )
    }
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run the Poet desktop build from the source tree."
    mainClass.set(mainClassName)
    classpath = sourceSets.main.get().runtimeClasspath
}

/**
 * Everything jpackage needs in one directory: the module jar plus every
 * runtime dependency, flat. jpackage takes the directory, not a classpath.
 */
val jpackageInput = layout.buildDirectory.dir("jpackage-input")

val collectRuntime by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stage the app jar and its runtime dependencies for jpackage."
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(jpackageInput)
}

/**
 * Builds the .deb with jpackage, which is bundled with the JDK 17 toolchain
 * this project already uses. The package carries its own runtime, so the
 * installed app has no Java dependency of its own.
 */
tasks.register<Exec>("packageDeb") {
    group = "distribution"
    description = "Build an installable .deb (Debian/Ubuntu)."
    dependsOn(collectRuntime)

    val outDir = layout.buildDirectory.dir("deb")
    doFirst { outDir.get().asFile.mkdirs() }

    commandLine(
        "jpackage",
        "--type", "deb",
        "--name", "poet-music",
        "--app-version", appVersion,
        "--description", "Offline-first, pastel-themed music player",
        "--vendor", "Poet Music",
        "--copyright", "Poet Music",
        "--input", jpackageInput.get().asFile.absolutePath,
        "--main-jar", tasks.jar.get().archiveFileName.get(),
        "--main-class", mainClassName,
        "--dest", outDir.get().asFile.absolutePath,
        "--icon", file("packaging/poet.png").absolutePath,
        "--linux-shortcut",
        "--linux-menu-group", "AudioVideo;Audio;Player;",
        "--linux-deb-maintainer", debMaintainer,
        "--linux-app-category", "sound",
        // The runtime libraries the app binds through JNA, all of them stock on
        // a Debian/Ubuntu desktop (docs/desktop-app-plan.md §5). Nothing native
        // is bundled: the window is the system's WebKitGTK and playback is the
        // system's GStreamer, so the package stays small and picks up the
        // distro's security updates.
        //   gtk3 + webkit2gtk      the native window
        //   gstreamer + base/good  playbin, decoding, the audio sinks and
        //                          equalizer-10bands
        //   plugins-bad            soundtouch, where `pitch` (the speed chip)
        //                          comes from
        //   libav                  the remaining codecs the Android build accepts
        // The alternations cover the t64 rename on Ubuntu 24.04 and the
        // webkit2gtk 4.0 -> 4.1 transition; WebKitWindow tries both sonames.
        "--linux-package-deps",
        "libgtk-3-0 | libgtk-3-0t64, " +
            "libwebkit2gtk-4.1-0 | libwebkit2gtk-4.0-37, " +
            "libglib2.0-0 | libglib2.0-0t64, " +
            "libgstreamer1.0-0, gstreamer1.0-plugins-base, gstreamer1.0-plugins-good, " +
            "gstreamer1.0-plugins-bad, gstreamer1.0-libav"
    )
}
