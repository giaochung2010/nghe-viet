plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.ngheviet.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ngheviet.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "onnx" }
    packaging { jniLibs { useLegacyPackaging = false } }
}
dependencies {
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    implementation("com.google.mlkit:translate:17.0.3")
    testImplementation("junit:junit:4.13.2")
}
