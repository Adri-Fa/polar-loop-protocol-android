plugins {
    id("com.android.library") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.3.21"
}

android {
    // Namespace only -- this module has no resources/R class of its own, so it's just an id, not
    // a claim that everything here lives under this package.
    namespace = "com.polarloopdebugger.polarprotocol"
    // androidx.health.connect:connect-client:1.1.0 (used by HealthConnectMapper) requires
    // compiling against API 36+.
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // HealthConnectMapper only -- everything else in this module is pure Kotlin/plain Android BLE.
    implementation("androidx.health.connect:connect-client:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
