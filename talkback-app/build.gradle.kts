plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.talkback.appprod"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.talkback.appprod"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Field / RC smoke: sign release with debug keystore so assembleRelease is installable on lab devices.
            signingConfig = signingConfigs.getByName("debug")
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

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    val prodAar = rootProject.file("libs/talkback-production-stream-webrtc-android.aar")
    val pnsrdInstAar = rootProject.file("libs/pnsrd-inst-1-stream-webrtc-android.aar")
    val webrtcAar = when {
        prodAar.exists() -> prodAar
        pnsrdInstAar.exists() -> pnsrdInstAar
        else -> null
    }
    if (webrtcAar != null) {
        sourceSets {
            getByName("main") {
                jniLibs.srcDir(layout.buildDirectory.dir("webrtc-packaged-jni"))
            }
        }
    }
}

dependencies {
    implementation(project(":android-board-talkback"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")

    androidTestImplementation(project(":android-board-talkback"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

val prodAarForJni = rootProject.file("libs/talkback-production-stream-webrtc-android.aar")
val pnsrdInstAarForJni = rootProject.file("libs/pnsrd-inst-1-stream-webrtc-android.aar")
val packagedWebrtcAar = when {
    prodAarForJni.exists() -> prodAarForJni
    pnsrdInstAarForJni.exists() -> pnsrdInstAarForJni
    else -> null
}
if (packagedWebrtcAar != null) {
    val extractPackagedWebrtcJni = tasks.register<Copy>("extractPackagedWebrtcJni") {
        from(zipTree(packagedWebrtcAar)) {
            include("jni/arm64-v8a/**")
            include("jni/armeabi-v7a/**")
        }
        into(layout.buildDirectory.dir("webrtc-packaged-jni"))
    }
    tasks.named("preBuild") { dependsOn(extractPackagedWebrtcJni) }
}
