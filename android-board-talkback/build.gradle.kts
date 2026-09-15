plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.talkback"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/androidTest/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric wire harness vs plain-JUnit crypto conformance must not share a JVM.
                it.forkEvery = 1
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    // org.webrtc:google-webrtc 已从公共仓库下架，使用 Stream 维护的 Maven 发行版（同 org.webrtc 包名）
    val prodAar = rootProject.file("libs/talkback-production-stream-webrtc-android.aar")
    val pnsrdInstAar = rootProject.file("libs/pnsrd-inst-1-stream-webrtc-android.aar")
    when {
        prodAar.exists() -> {
            logger.lifecycle("production-f8-fix1: using local production AAR ${prodAar.absolutePath}")
            implementation("io.getstream:stream-webrtc-android:1.3.10-talkback-f8-fix1")
        }
        pnsrdInstAar.exists() -> {
            logger.lifecycle("pnsrd-inst-1: using local instrumentation AAR ${pnsrdInstAar.absolutePath}")
            implementation("io.getstream:stream-webrtc-android:1.3.10-pnsrd-inst-1")
        }
        else -> {
            implementation("io.getstream:stream-webrtc-android:1.3.10")
        }
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.google.code.gson:gson:2.11.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
