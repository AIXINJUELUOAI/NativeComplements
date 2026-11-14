plugins { id("com.android.application") }
android {
    namespace = "dev.nativecomplements.navdemo"
    compileSdkVersion("android-37.0")
    defaultConfig { applicationId = "dev.nativecomplements.navdemo"; minSdk = 34; targetSdk = 37; versionCode = 3; versionName = "0.1.2" }
    signingConfigs { create("demoUpdate") { storeFile = rootProject.file(".local/demo.keystore"); storePassword = "android"; keyAlias = "demo"; keyPassword = "android" } }
    buildTypes { getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("demoUpdate") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true; unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2") } }
}
dependencies {
    compileOnly(files("../app/libs/libxposed-api-101.0.1.jar"))
    testRuntimeOnly(files("../app/libs/libxposed-api-101.0.1.jar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
}
