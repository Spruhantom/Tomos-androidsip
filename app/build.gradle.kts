plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val premiumApiUrl = providers.gradleProperty("tomosPremiumApiUrl")
    .orElse(providers.environmentVariable("TOMOS_PREMIUM_API_URL"))
    .orElse("")
    .get()
val premiumPublicKey = providers.gradleProperty("tomosPremiumPublicKey")
    .orElse(providers.environmentVariable("TOMOS_PREMIUM_PUBLIC_KEY"))
    .orElse("")
    .get()
val escapedPremiumPublicKey = premiumPublicKey
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", "")
    .replace("\n", "\\n")

android {
    namespace = "uk.co.tomos.sip"
    compileSdk = 35

    defaultConfig {
        buildConfigField("String", "TOMOS_PREMIUM_API_URL", "\"$premiumApiUrl\"")
        buildConfigField("String", "TOMOS_PREMIUM_PUBLIC_KEY", "\"$escapedPremiumPublicKey\"")
        applicationId = "uk.co.tomos.sip"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("versionCode").orElse("1").get().toInt()
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug")
        getByName("release") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

// Release APKs must never be published with an empty, malformed, or test-only
// Premium verification key. Debug builds intentionally remain buildable without
// production signing material so contributors can work on the free SIP client.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        require(premiumPublicKey.isNotBlank()) {
            "Release builds require -PtomosPremiumPublicKey or TOMOS_PREMIUM_PUBLIC_KEY."
        }
        val pemBody = premiumPublicKey
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .filterNot { it.isWhitespace() }
        val decoded = runCatching { java.util.Base64.getDecoder().decode(pemBody) }.getOrNull()
        val ed25519SubjectPublicKeyInfoPrefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
            0x70, 0x03, 0x21, 0x00
        )
        require(decoded != null && decoded.size == 44 &&
            decoded.copyOfRange(0, ed25519SubjectPublicKeyInfoPrefix.size)
                .contentEquals(ed25519SubjectPublicKeyInfoPrefix)) {
            "TOMOS_PREMIUM_PUBLIC_KEY must be a valid Ed25519 SubjectPublicKeyInfo PEM public key."
        }
        require(!premiumPublicKey.contains("PRIVATE KEY")) {
            "Only the Ed25519 public key may be embedded in the Android application."
        }
    }
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
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.80")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
