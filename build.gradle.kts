buildscript {
    dependencies {
        // AGP 9 ships built-in Kotlin pinned to an older KGP; lift it so the
        // Compose compiler plugin below matches the compiler version.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
