plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Opt-in separate install identity for acceptance, preserving DEV/release data.
val acceptanceBuild = providers.gradleProperty("agentHubAcceptance").map {
    require(it == "true" || it == "false") { "agentHubAcceptance must be true or false" }
    it.toBoolean()
}.getOrElse(false)

android {
    namespace = "com.agenthub.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.codexmobile.personal"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug {
            applicationIdSuffix = if (acceptanceBuild) ".acceptance" else ".debug"
            versionNameSuffix = if (acceptanceBuild) "-acceptance" else "-dev"
        }
        release { isMinifyEnabled = false }
    }
    val releaseStoreFile = providers.gradleProperty("codexReleaseStoreFile")
        .orElse(providers.environmentVariable("CODEX_RELEASE_STORE_FILE"))
    val releaseStorePassword = providers.gradleProperty("codexReleaseStorePassword")
        .orElse(providers.environmentVariable("CODEX_RELEASE_STORE_PASSWORD"))
    val releaseKeyAlias = providers.gradleProperty("codexReleaseKeyAlias")
        .orElse(providers.environmentVariable("CODEX_RELEASE_KEY_ALIAS"))
    val releaseKeyPassword = providers.gradleProperty("codexReleaseKeyPassword")
        .orElse(providers.environmentVariable("CODEX_RELEASE_KEY_PASSWORD"))
    if (releaseStoreFile.isPresent && releaseStorePassword.isPresent &&
        releaseKeyAlias.isPresent && releaseKeyPassword.isPresent
    ) {
        signingConfigs {
            create("codexRelease") {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("codexRelease")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { animationsDisabled = true }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("packages/protocol/fixtures/mobile"))
}

dependencies {
    implementation(project(":mobile-core"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // CommonMark AST renders through native Compose; no HTML/WebView execution.
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.camera:camera-camera2:1.6.0")
    implementation("androidx.camera:camera-lifecycle:1.6.0")
    implementation("androidx.camera:camera-view:1.6.0")
    implementation("com.google.zxing:core:3.5.4")
    // Platform transports and API26-compatible X25519; domain rules remain in KMP.
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.86")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
