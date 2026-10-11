// Versions are pinned to a combination known to work together:
// Android Gradle Plugin 8.7.3 + Gradle 8.9 + Kotlin 2.0.21 (Compose compiler ships with Kotlin 2.x).
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
