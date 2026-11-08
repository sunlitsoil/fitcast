plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.fitcast.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.fitcast.app"
        minSdk = 28 // Pleos Connect 요구사항: Android API 28 이상
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // release 서명: 키 저장소와 비밀번호 파일은 저장소 밖에 두고 환경 변수로만 넘긴다
    val ks = System.getenv("FITCAST_KEYSTORE")
    val ksPass = System.getenv("FITCAST_KEYSTORE_PASS_FILE")?.let { file(it).readText().trim() }
    signingConfigs {
        if (ks != null && ksPass != null) create("release") {
            storeFile = file(ks); storePassword = ksPass; keyAlias = "fitcast"; keyPassword = ksPass
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    // Pleos Connect SDK — 버전은 공식 문서 기준(2026-10-30 확인)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
}
