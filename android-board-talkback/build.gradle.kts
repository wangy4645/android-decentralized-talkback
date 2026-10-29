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

    // AQC Phase 2 desk gate: avoid compiling unrelated stale unit tests.
    // Usage: ./gradlew :android-board-talkback:testDebugUnitTest -PaqcDeskOnly --tests "com.talkback.core.conference.aqc.*"
    if (project.hasProperty("aqcDeskOnly")) {
        sourceSets.getByName("test") {
            java.setSrcDirs(
                listOf("src/test/java/com/talkback/core/conference/aqc"),
            )
        }
        logger.lifecycle("aqcDeskOnly: unit test sources limited to com.talkback.core.conference.aqc")
    }
    // B-R per-source playout restore desk gate (avoid unrelated stale unit tests).
    if (project.hasProperty("playoutBrDeskOnly")) {
        sourceSets.getByName("test") {
            java.setSrcDirs(listOf("src/test-playout-br/java"))
        }
        logger.lifecycle("playoutBrDeskOnly: unit test sources limited to src/test-playout-br")
    }
    // Mix A/B combine-mode desk (avoid unrelated stale unit tests).
    if (project.hasProperty("mixAbDeskOnly")) {
        sourceSets.getByName("test") {
            java.setSrcDirs(listOf("src/test-mix-ab/java"))
        }
        logger.lifecycle("mixAbDeskOnly: unit test sources limited to src/test-mix-ab")
    }
    if (project.hasProperty("b1DeskOnly")) {
        sourceSets.getByName("test") {
            java.setSrcDirs(listOf("src/test-b1-observe/java"))
        }
        logger.lifecycle("b1DeskOnly: unit test sources limited to src/test-b1-observe")
    }
    if (project.hasProperty("groupIceChannelSyncOnly")) {
        sourceSets.getByName("test") {
            java.setSrcDirs(listOf("src/test-group-ice-channel-sync/java"))
        }
        logger.lifecycle("groupIceChannelSyncOnly: unit test sources limited to src/test-group-ice-channel-sync")
    }
    if (project.hasProperty("profile01RejoinDeskOnly")) {
        sourceSets.getByName("test") {
            java.setSrcDirs(listOf("src/test-profile01-rejoin/java"))
        }
        logger.lifecycle("profile01RejoinDeskOnly: unit test sources limited to src/test-profile01-rejoin")
    }
    // Profile01 supplement delivery gate: PR-2 coordinator tests + desk + bridge (no stale unit tests).
    // ./gradlew :android-board-talkback:testDebugUnitTest -Pprofile01SupplementDeliveryGate --tests "com.talkback.core.conference.session.integration.*"
    if (
        project.hasProperty("profile01SupplementDeliveryGate") ||
            project.hasProperty("profile01SupplementPr2Only")
    ) {
        sourceSets.getByName("test") {
            java.setSrcDirs(
                listOf(
                    "src/test-profile01-supplement-pr2/java",
                    "src/test-profile01-supplement-delivery-gate/java",
                ),
            )
        }
        logger.lifecycle(
            "profile01SupplementDeliveryGate: PR-2 + desk + bridge + establishment fixtures only",
        )
    }
    if (project.hasProperty("catalogL1bDeskOnly")) {
        sourceSets.getByName("test") {
            java.setSrcDirs(listOf("src/test-catalog-l1b/java"))
        }
        logger.lifecycle("catalogL1bDeskOnly: unit test sources limited to src/test-catalog-l1b")
    }
    if (project.hasProperty("mediaLifecycleDeskOnly")) {
        sourceSets.getByName("test") {
            java.setSrcDirs(
                listOf(
                    "src/test/java/com/talkback/core/media",
                    "src/test-gplb-hold/java",
                ),
            )
        }
        logger.lifecycle("mediaLifecycleDeskOnly: unit test sources limited to media + gplb-hold")
    }
    // EP operator authority batch (field helper): compile only the establishment harness + signing fixtures,
    // skipping unrelated stale unit tests. Used by scripts/ep-establishment-operator-authority-batch.ps1.
    // Kotlin compile does not honour include/exclude filters on Android source sets, so named gates stage
    // the exact files they need into an isolated source root (unrelated stale unit tests are skipped).
    val unitTestFileGates: Map<String, List<String>> =
        mapOf(
            // scripts/ep-establishment-operator-authority-batch.ps1
            "epAuthorityOnly" to
                listOf(
                    "com/talkback/core/session/gbc/trust/profile/OperationalProfileTestSigning.kt",
                    "com/talkback/core/session/gbc/trust/profile/establishment/EstablishmentProfileRevisionFixtureSupport.kt",
                    "com/talkback/core/session/gbc/trust/profile/establishment/EstablishmentOperatorAuthorityFieldHarness.kt",
                    "com/talkback/core/session/gbc/trust/profile/establishment/EstablishmentOperatorAuthorityFieldHarnessTest.kt",
                ),
            "linkQualificationDeskOnly" to
                listOf(
                    "com/talkback/core/signaling/link/LinkQualificationTrackerTest.kt",
                ),
            "groupL0IceRestartDeskOnly" to
                listOf(
                    "com/talkback/core/session/GroupL0IceRestartAdmissionTest.kt",
                    "com/talkback/core/webrtc/IceRestartPrepareDecisionTest.kt",
                    "com/talkback/core/webrtc/SrdAdmissionDecisionTest.kt",
                ),
            "cutoverOwnershipDeskOnly" to
                listOf(
                    "com/talkback/core/conference/session/integration/cutover/AudibleOwnershipControllerTest.kt",
                    "com/talkback/core/conference/session/integration/cutover/MulticastAudibleAutomaticCutoverTest.kt",
                    "com/talkback/core/conference/session/integration/cutover/MulticastAudibleCutoverReevaluationTest.kt",
                ),
            "productionPlayoutWriteDeskOnly" to
                listOf(
                    "com/talkback/core/conference/runtime/ProductionPlayoutWriteTelemetryDeskTest.kt",
                ),
            "productionPlayoutTickDeskOnly" to
                listOf(
                    "com/talkback/core/conference/runtime/ProductionPlayoutTickTelemetryDeskTest.kt",
                ),
            "productionPlayoutDeadlineDeskOnly" to
                listOf(
                    "com/talkback/core/conference/session/integration/ProductionPlayoutDeadlinePumpTest.kt",
                    "com/talkback/core/conference/session/integration/ProductionPlayoutDeadlineIsolationDeskTest.kt",
                    "com/talkback/core/conference/session/integration/Profile01ShadowSr4PlayTest.kt",
                    "com/talkback/core/conference/runtime/ProductionPlayoutTickTelemetryDeskTest.kt",
                ),
            "conferenceUiRosterDeskOnly" to
                listOf(
                    "com/talkback/core/session/ConferenceParticipantManagerTest.kt",
                    "com/talkback/core/session/ConferenceParticipantProjectorTest.kt",
                    "com/talkback/core/session/ConferenceRuntimeProjectorTest.kt",
                ),
            "profile01SourceMembershipDeskOnly" to
                listOf(
                    "com/talkback/core/conference/session/integration/MeetingProfile01SourceMembershipContextRepublicationTest.kt",
                ),
        )
    unitTestFileGates.forEach { (gate, files) ->
        if (project.hasProperty(gate)) {
            val staging = layout.buildDirectory.dir("unit-test-gate-src/$gate").get().asFile
            staging.deleteRecursively()
            copy {
                from("src/test/java") { include(files) }
                into(staging)
            }
            sourceSets.getByName("test") {
                java.setSrcDirs(listOf(staging))
            }
            logger.lifecycle("$gate: unit test sources limited to ${files.size} staged file(s)")
        }
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
