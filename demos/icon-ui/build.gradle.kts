plugins { id("com.android.application") version "8.13.2" }

android {
    namespace = "dev.local.nativecomplements.lab"
    compileSdkVersion("android-37.0")
    defaultConfig {
        applicationId = "dev.local.nativecomplements.lab"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    signingConfigs {
        create("localDemo") {
            storeFile = file("../../.local/demo.keystore")
            storePassword = "android"
            keyAlias = "demo"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("release") { signingConfig = signingConfigs.getByName("localDemo") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
