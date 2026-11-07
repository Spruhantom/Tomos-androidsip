plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "uk.co.tomos.sip"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.co.tomos.sip"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("versionCode").orElse("1").get().toInt()
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("debug")
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.media:media:1.2.0")
    implementation("org.linphone:linphone-sdk-android:5.5.38")
    implementation("com.google.code.gson:gson:2.11.0")\n    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    testImplementation("junit:junit:4.13.2")\n    testImplementation("org.bouncycastle:bcprov-jdk18on:1.80")
}
