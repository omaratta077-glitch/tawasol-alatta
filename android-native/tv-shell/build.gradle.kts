plugins {
    id("com.android.application")
}

android {
    namespace = "com.alatta.tv"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.alatta.tv"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}
