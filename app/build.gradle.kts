plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yuzoyunu.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yuzoyunu.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }

    // Model dosyası sıkıştırılmamalı (MediaPipe doğrudan belleğe haritalar)
    androidResources {
        noCompress += listOf("task", "tflite")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Yüz mesh + blendshape (yüz ifadesi) tespiti
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}

// Yüz modeli (~4 MB) yoksa derleme sırasında otomatik indirilir.
val modelFile = file("src/main/assets/face_landmarker.task")
val downloadFaceModel by tasks.registering {
    onlyIf { !modelFile.exists() }
    doLast {
        modelFile.parentFile.mkdirs()
        val url = "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
        uri(url).toURL().openStream().use { input ->
            modelFile.outputStream().use { out -> input.copyTo(out) }
        }
        println("Model indirildi: ${modelFile.length()} bayt")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(downloadFaceModel)
}
