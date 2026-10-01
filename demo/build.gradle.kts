import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Backend address and demo key live outside git: local.properties (next to
// sdk.dir) or the environment. See the README.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun demoSetting(name: String, default: String): String =
    localProperties.getProperty(name) ?: System.getenv(name) ?: default

android {
    namespace = "ai.tryverso.connect.demo"
    compileSdk = 36
    defaultConfig {
        applicationId = "ai.tryverso.connect.demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "DEMO_BACKEND_URL", "\"${demoSetting("DEMO_BACKEND_URL", "https://verso-partner-demo.example.workers.dev")}\"")
        buildConfigField("String", "DEMO_KEY", "\"${demoSetting("DEMO_KEY", "replace-me")}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":versoconnect"))
    implementation("androidx.appcompat:appcompat:1.7.0")
}
