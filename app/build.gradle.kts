plugins {
    id("com.android.application")
}

android {
    namespace = "nz.symbiofoundry.robotx"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "nz.symbiofoundry.robotx"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")
 implementation(files("libs/sherpa-onnx-1.13.8.aar"))
}
