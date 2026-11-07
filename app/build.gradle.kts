plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.classboard.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.classboard.app"
        minSdk = 26          // Android 8.0 전자칠판 지원
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 별도 키가 없으면 디버그 키로 서명해 바로 설치할 수 있게 함
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 앱 자체는 외부 라이브러리 없이 안드로이드 기본 API만 사용 (PdfRenderer, Canvas)
    testImplementation("junit:junit:4.13.2")
}
