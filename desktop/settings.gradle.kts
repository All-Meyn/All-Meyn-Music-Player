// The Windows desktop app: its own Gradle build, so the Android build is untouched.
// Run with the repository's wrapper: ./gradlew -p desktop run | test | packageMsi
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "all-meyn-desktop"
