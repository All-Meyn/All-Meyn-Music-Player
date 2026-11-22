import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // Same Kotlin as the Android app; Compose Multiplatform 1.12.2 (Oct 2026) supports it.
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

dependencies {
    // Direct coordinates (the plugin's compose.* aliases are deprecated since 1.10).
    implementation("org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.12.2")
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03") // the Material3 aligned with 1.12.2
    testImplementation(kotlin("test"))
}

// The library model (Track, Album, Artist, folders, sorting, search) is the Android app's own pure
// Kotlin file, compiled here as well: both apps group, sort and search the library the same way.
val sharedSources = tasks.register<Sync>("sharedSources") {
    from("../app/src/main/java") { include("com/allmeyn/player/library/Models.kt") }
    into(layout.buildDirectory.dir("shared-src"))
}
kotlin.sourceSets.main { kotlin.srcDir(sharedSources) }

// The AM mark comes straight from the Android app's vector (one source of truth for the logo).
// The window/taskbar icon is the Play Store artwork.
tasks.processResources {
    from("../app/src/main/res/drawable/ic_brand_mark.xml")
    from("../branding/play-store-icon-512.png") { rename { "all-meyn-icon.png" } }
}

// The engine DLL is built by CI (desktop/native) into resources/windows-x64; tests that need it
// look there and are skipped when it's missing.
tasks.test {
    useJUnitPlatform()
    systemProperty("allmeyn.engineDir", layout.projectDirectory.dir("resources/windows-x64").asFile.absolutePath)
    systemProperty("allmeyn.assetsDir", layout.projectDirectory.dir("../app/src/androidTest/assets").asFile.absolutePath)
}

compose.desktop {
    application {
        mainClass = "com.allmeyn.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "All-Meyn Player"
            packageVersion = "0.1.0"
            description = "Your library. Your hardware. All yours."
            vendor = "All-Meyn"
            // Per-OS/arch files copied next to the app: resources/windows-x64/allmeyn_desktop.dll.
            appResourcesRootDir.set(layout.projectDirectory.dir("resources"))
            windows {
                iconFile.set(layout.projectDirectory.file("icons/all-meyn.ico"))
                menuGroup = "All-Meyn"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Fixed, so a newer installer upgrades the installed app instead of adding a second one.
                upgradeUuid = "6f1c7a52-3d0e-4b8a-9c41-2e7d5b9a0f13"
            }
        }
    }
}
