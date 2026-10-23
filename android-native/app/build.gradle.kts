plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appGalleryBuild = providers.gradleProperty("alatta.distribution").orNull == "appgallery"
// Assemble a slim asset tree; retain only the opening page inside the APK.
val slimMushafAssets by tasks.registering(Sync::class) {
    from("src/main/assets") { exclude("mushaf/pages/**") }
    from("src/main/assets/mushaf/pages/001.svg.gz") { into("mushaf/pages") }
    into(layout.buildDirectory.dir("generated/slimAssets"))
}
val syncLegalAssets by tasks.registering(Copy::class) {
    from(rootProject.file("../cloudflare-site/public")) { include("privacy.html", "terms.html", "data-rights.html") }
    into(layout.buildDirectory.dir("generated/legalAssets/legal"))
}

android {
    namespace = "com.alatta.nativeapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alatta.nativeapp"
        minSdk = 26
        targetSdk = 35
        // v1.0.134 release: audited media safety, inbox and Quran performance.
        versionCode = 253
        versionName = "1.0.153"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "IS_APPGALLERY", appGalleryBuild.toString())
        val hmsId = (System.getenv("ALATTA_HMS_APP_ID") ?: "").trim().takeIf { it.matches(Regex("[0-9]{1,20}")) } ?: ""
        buildConfigField("String", "HMS_APP_ID", "\"$hmsId\"")
        buildConfigField("boolean", "HMS_CONFIGURED", (hmsId.isNotBlank() && file("src/main/assets/agconnect-services.json").isFile).toString())
        manifestPlaceholders["hmsAppId"] = "appid=$hmsId"
        manifestPlaceholders["distributionChannel"] = if (appGalleryBuild) "appgallery" else "direct"
    }

    // Firebase's public Android configuration is supplied by GitHub Actions vars.
    // Empty values intentionally leave background push inactive without breaking
    // local builds or requiring a checked-in google-services.json.
    buildFeatures { compose = true; buildConfig = true }
    val pushFields = mapOf(
        "PUSH_APP_ID" to "ALATTA_FCM_APP_ID",
        "PUSH_SENDER_ID" to "ALATTA_FCM_SENDER_ID",
        "PUSH_PROJECT_ID" to "ALATTA_FCM_PROJECT_ID",
        "PUSH_API_KEY" to "ALATTA_FCM_API_KEY"
    )
    defaultConfig {
        pushFields.forEach { (name, envName) ->
            val value = (System.getenv(envName) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", name, "\"$value\"")
        }
    }

    // MUST remain inside android {}, before buildTypes.
    signingConfigs {
        create("officialRelease") {
            val path = System.getenv("ALATTA_KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("ALATTA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ALATTA_KEY_ALIAS")
                keyPassword = System.getenv("ALATTA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Keep both ARM phone architectures; debug retains x86 for device tests.
            ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
            signingConfig = signingConfigs.getByName("officialRelease")
        }
    }
    sourceSets.getByName("main").assets.setSrcDirs(listOf(layout.buildDirectory.dir("generated/slimAssets").get().asFile))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/legalAssets"))
    if (appGalleryBuild) {
        sourceSets.getByName("release").manifest.srcFile("src/appgallery/AndroidManifest.xml")
        sourceSets.getByName("debug").manifest.srcFile("src/appgallery/AndroidManifest.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions.unitTests.isIncludeAndroidResources = true
}
tasks.named("preBuild").configure { dependsOn(syncLegalAssets, slimMushafAssets) }
// Gradle 8 must see explicit dependencies for generated asset inputs.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(syncLegalAssets, slimMushafAssets) }



dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation("com.batoulapps.adhan:adhan:1.2.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.caverock:androidsvg:1.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.firebase:firebase-messaging:24.1.2")
    implementation("com.google.android.gms:play-services-base:18.5.0")
    implementation("com.huawei.hms:push:6.13.0.300")
    // Native C++ WebRTC camera/audio/renderers; no call HTML page.
    implementation("io.github.webrtc-sdk:android:137.7151.03")
}
