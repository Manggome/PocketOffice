import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// keystore/release.jks 가 있으면 릴리스 서명, 없으면 고정 debug 키로 서명한다.
val releaseKeystore = rootProject.file("keystore/release.jks")

// 저장소에 고정해 둔 debug 서명 키.
// 빌드마다 키가 바뀌면 기존 앱을 지워야만 설치되므로, 키를 고정해 덮어쓰기 업데이트가 되게 한다.
val debugKeystore = rootProject.file("keystore/debug.jks")

// 편집기 엔진은 APK 에 넣지 않고 별도 릴리스(engine-<id>)에서 처음 한 번 받는다 (core/EngineStore.kt).
// id 는 엔진 원본 커밋과 추리는 스크립트의 해시다. CI 의 build.yml 도 같은 식으로 계산한다:
//   cat engine/ENGINE_REF engine/prepare-engine.mjs | sha256sum | cut -c1-12
val engineId: String = run {
    val md = MessageDigest.getInstance("SHA-256")
    listOf("engine/ENGINE_REF", "engine/prepare-engine.mjs").forEach { md.update(rootProject.file(it).readBytes()) }
    md.digest().joinToString("") { "%02x".format(it) }.take(12)
}

android {
    namespace = "kr.neptune.pocketoffice"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.neptune.pocketoffice"
        // WebAssembly 와 최신 JS 를 쓰는 편집기라 너무 오래된 WebView 는 의미가 없다
        minSdk = 26
        targetSdk = 35

        // CI 실행번호로 버전을 매긴다. 앱이 릴리스의 latest.json 과 비교해
        // 새 버전이 있으면 스스로 받아 설치할 수 있게 하기 위함.
        versionCode = 1 + ((System.getenv("GITHUB_RUN_NUMBER") ?: "").toIntOrNull() ?: 0)
        versionName = "1.0." + ((System.getenv("GITHUB_RUN_NUMBER") ?: "0"))

        buildConfigField("String", "ENGINE_ID", "\"$engineId\"")
    }

    signingConfigs {
        if (debugKeystore.exists()) {
            create("fixedDebug") {
                storeFile = debugKeystore
                storePassword = "pocketoffice"
                keyAlias = "pocketoffice"
                keyPassword = "pocketoffice"
            }
        }
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "pocketoffice"
                keyAlias = System.getenv("KEY_ALIAS") ?: "pocketoffice"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "pocketoffice"
            }
        }
    }

    buildTypes {
        release {
            // 코드가 작고, 난독화로 얻는 이득보다 JavascriptInterface 가 깨질 위험이 크다
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore.exists()) {
                signingConfigs.getByName("release")
            } else if (debugKeystore.exists()) {
                signingConfigs.getByName("fixedDebug")
            } else {
                null
            }
        }
        debug {
            isMinifyEnabled = false
            if (debugKeystore.exists()) {
                signingConfig = signingConfigs.getByName("fixedDebug")
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCY",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
