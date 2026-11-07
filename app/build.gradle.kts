plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val tomosSigningStoreFile = providers.environmentVariable("TOMOS_SIGNING_STORE_FILE").orNull
val tomosSigningStorePassword = providers.environmentVariable("TOMOS_SIGNING_STORE_PASSWORD").orNull
val tomosSigningKeyAlias = providers.environmentVariable("TOMOS_SIGNING_KEY_ALIAS").orNull
val tomosSigningKeyPassword = providers.environmentVariable("TOMOS_SIGNING_KEY_PASSWORD").orNull
val hasTomosSigningKey = listOf(
    tomosSigningStoreFile,
    tomosSigningStorePassword,
    tomosSigningKeyAlias,
    tomosSigningKeyPassword
).all { !it.isNullOrBlank() }

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

    signingConfigs {
        if (hasTomosSigningKey) {
            create("tomosCi") {
                storeFile = file(tomosSigningStoreFile!!)
                storePassword = tomosSigningStorePassword
                keyAlias = tomosSigningKeyAlias
                keyPassword = tomosSigningKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasTomosSigningKey) {
                signingConfig = signingConfigs.getByName("tomosCi")
            }
        }
        getByName("release") {
            isMinifyEnabled = false
            if (hasTomosSigningKey) {
                signingConfig = signingConfigs.getByName("tomosCi")
            }
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
    implementation("androidx.media:media:1.2.0")
    implementation("org.linphone:linphone-sdk-android:5.5.38")
    testImplementation("junit:junit:4.13.2")
}
