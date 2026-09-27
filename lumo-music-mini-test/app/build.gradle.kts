plugins {
    id("com.android.application")
}

android {
    namespace = "com.lumomusic.mini"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lumomusic.mini"
        minSdk = 26
        targetSdk = 37
        versionCode = 1901
        versionName = "1.9.0-test"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
