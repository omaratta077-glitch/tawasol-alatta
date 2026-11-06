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
        versionCode = 5
        versionName = "1.1.3"
    }
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
            signingConfig = signingConfigs.getByName("officialRelease")
        }
    }
}
