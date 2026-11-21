plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied (AGP 9 compiles Kotlin itself): declared only so its Kotlin Gradle plugin version
    // (2.4.21) is the one AGP's built-in Kotlin uses, instead of AGP's bundled 2.2.10.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
