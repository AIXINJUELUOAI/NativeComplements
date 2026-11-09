plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.local.sunwindowdemo"
    compileSdkVersion("android-37.0")
    defaultConfig {
        // Preserve Demo8.2 data, permissions, LSPosed scope and runtime identity.
        applicationId = "dev.local.sunwindowdemo"
        minSdk = 34
        targetSdk = 37
        versionCode = 44
        versionName = "0.4.4"
    }
    signingConfigs {
        create("demoUpdate") {
            storeFile = rootProject.file(".local/demo.keystore")
            storePassword = "android"
            keyAlias = "demo"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("release") {
            // Preserve reflection targets, hook entry points and runtime bytecode semantics.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("demoUpdate")
        }
        getByName("debug") { signingConfig = signingConfigs.getByName("demoUpdate") }
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging.resources {
        excludes.removeAll(setOf("META-INF/LICENSE", "META-INF/NOTICE", "/META-INF/LICENSE", "/META-INF/NOTICE"))
        merges += setOf("META-INF/LICENSE", "META-INF/NOTICE")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            it.systemProperty("nativecomplements.screenshots", rootProject.file("verification/screenshots").absolutePath)
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    compileOnly(files("libs/libxposed-api-101.0.1.jar"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    // Expressive APIs are public in this pinned preview; 1.4 stable hides them.
    implementation("androidx.compose.material3:material3:1.5.0-alpha01")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
