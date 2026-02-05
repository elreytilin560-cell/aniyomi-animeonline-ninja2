import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget as KotlinJvmTarget

allprojects {
    repositories {
        mavenCentral()
        google()
        maven(url = "https://jitpack.io")
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(KotlinJvmTarget.JVM_1_8)
        }
    }
}
plugins {
    id("com.android.library") version "8.1.0" apply false
    kotlin("android") version "1.9.0" apply false
}
