plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.parkwitness.app"
    compileSdk = 35
    buildToolsVersion = "36.0.0"
    useLibrary("android.car", false)
    defaultConfig {
        applicationId = "com.parkwitness.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    flavorDimensions += "vehicle"
    productFlavors {
        create("demo") {
            dimension = "vehicle"
            applicationIdSuffix = ".demo"
            manifestPlaceholders["appLabel"] = "Park Witness · DEMO"
        }
        create("pleos") {
            dimension = "vehicle"
            manifestPlaceholders["appLabel"] = "Park Witness · PLEOS TEST"
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    "pleosImplementation"("ai.pleos.playground:Vehicle:2.3.2")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
}
