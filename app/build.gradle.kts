import java.util.Properties
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.lumina.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lumina.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 146
        versionName = "1.24.2"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("String", "TMDB_API_KEY", "\"${localProps.getProperty("TMDB_API_KEY", "")}\"")
        buildConfigField("String", "MDBLIST_API_KEY", "\"${localProps.getProperty("MDBLIST_API_KEY", "")}\"")
    }

    signingConfigs {
        create("release") {
            val ks = rootProject.file("lumina.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = localProps.getProperty("LUMINA_STORE_PASSWORD", "")
                keyAlias = localProps.getProperty("LUMINA_KEY_ALIAS", "lumina")
                keyPassword = localProps.getProperty("LUMINA_KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (rootProject.file("lumina.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // libmpv 自带的 libc++_shared 去重
            pickFirsts += "**/libc++_shared.so"
        }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("dev.chrisbanes.haze:haze:1.5.0")

    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.7.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.7.1")
    implementation("org.jellycine.mpv:libmpv:1.0.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("androidx.tv:tv-material:1.0.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.github.promeg:tinypinyin:2.0.3")
}

// JVM-only JSON runtime; never included in the Android app or its dependency graph.
val samplesJson = configurations.create("samplesJson") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies.add(samplesJson.name, "org.json:json:20240303")

val samples = tasks.register<JavaExec>("samples") {
    group = "verification"
    description = "Run all sample suites and A1 reliability assertions (no credentials required)."
    val tests = tasks.named<KotlinCompile>("compileDebugUnitTestKotlin")
    val main = tasks.named<KotlinCompile>("compileDebugKotlin")
    dependsOn(tests, main)
    // Real JSON must precede the Android stub classes on the JVM classpath.
    classpath = files(samplesJson, tests.flatMap { it.destinationDirectory },
        main.flatMap { it.destinationDirectory }, tests.map { it.libraries })
    mainClass.set("com.lumina.tv.SamplesMain")
}
val p1Checks = tasks.register<JavaExec>("p1Checks") {
    group = "verification"
    description = "Check user-data mutations and library browse regressions."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.P1RegressionChecks")
}
val verifyOffline = tasks.register("verifyOffline") {
    group = "verification"
    description = "Mandatory offline logic gate: samples, A1, P1, P2 and P3 assertions."
    dependsOn(samples, p1Checks)
}
val p2Checks = tasks.register<JavaExec>("p2Checks") {
    group = "verification"
    description = "Check playback load ownership and failure-path recovery."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.player.P2PlaybackChecks")
}
verifyOffline.configure { dependsOn(p2Checks) }
val p3Checks = tasks.register<JavaExec>("p3Checks") {
    group = "verification"
    description = "Check serial progress delivery, exact ACKs and playback session closure."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.data.P3ProgressChecks")
}
verifyOffline.configure { dependsOn(p3Checks) }
val p4Checks = tasks.register<JavaExec>("p4Checks") {
    group = "verification"
    description = "Check track identity, negotiation preferences and playable episode resolution."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.P4Checks")
}
verifyOffline.configure { dependsOn(p4Checks) }
val p5Checks = tasks.register<JavaExec>("p5Checks") {
    group = "verification"
    description = "Check account content state, recommendation/preview cache boundaries and basePath."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.data.P5Checks")
}
verifyOffline.configure { dependsOn(p5Checks) }

val p6Checks = tasks.register<JavaExec>("p6Checks") {
    group = "verification"
    description = "Check favorites paging and playback diagnostics readback semantics."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.P6Checks")
}
verifyOffline.configure { dependsOn(p6Checks) }
val e1Checks = tasks.register<JavaExec>("e1Checks") {
    group = "verification"
    description = "Check pairing authorization, Latest response shapes and canonical server identity migration."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.E1Checks")
}
verifyOffline.configure { dependsOn(e1Checks) }
val e2Checks = tasks.register<JavaExec>("e2Checks") {
    group = "verification"
    description = "Check measured timelines, observed progress/Stop and seek confirmation deadlines."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.E2Checks")
}
verifyOffline.configure { dependsOn(e2Checks) }
val e4Checks = tasks.register<JavaExec>("e4Checks") {
    group = "verification"
    description = "Check container recovery, player lifecycles and playback controls."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.E4Checks")
}
verifyOffline.configure { dependsOn(e4Checks) }
val e5Checks = tasks.register<JavaExec>("e5Checks") {
    group = "verification"
    description = "Check season/cache, library/search paging, account focus and series source probes."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.E5Checks")
}
verifyOffline.configure { dependsOn(e5Checks) }
val b1Checks = tasks.register<JavaExec>("b1Checks") {
    group = "verification"
    description = "B1 security boundaries, asynchronous subtitles, resume and hidden controls assertions."
    dependsOn(samples)
    classpath = samples.get().classpath + files("src/test/resources")
    mainClass.set("com.lumina.tv.B1Checks")
}
verifyOffline.configure { dependsOn(b1Checks) }
val b2Checks = tasks.register<JavaExec>("b2Checks") {
    group = "verification"
    description = "B2 progress/session persistence and playback policy assertions."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.B2Checks")
}
verifyOffline.configure { dependsOn(b2Checks) }
val b3Checks = tasks.register<JavaExec>("b3Checks") {
    group = "verification"
    description = "B3 cache identity, season directory, next episode and source inventory assertions."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.data.B3Checks")
}
verifyOffline.configure { dependsOn(b3Checks) }
val b4Checks = tasks.register<JavaExec>("b4Checks") {
    group = "verification"
    description = "B4 watchdog, recovery routing and audio assertions."
    dependsOn(samples, p4Checks)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.player.B4Checks")
}
verifyOffline.configure { dependsOn(b4Checks) }
val b5Checks = tasks.register<JavaExec>("b5Checks") {
    group = "verification"
    description = "Check B5 player headers, renderer publication and host lifecycle regressions."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.B5Checks")
}
verifyOffline.configure { dependsOn(b5Checks) }
val b6Checks = tasks.register<JavaExec>("b6Checks") {
    group = "verification"
    description = "B6 detail picker, metadata identity/protocol, independent loading and preview gates."
    dependsOn(samples)
    classpath = samples.get().classpath + files("src/test/resources")
    mainClass.set("com.lumina.tv.B6Checks")
}
verifyOffline.configure { dependsOn(b6Checks) }
val b7Checks = tasks.register<JavaExec>("b7Checks") {
    group = "verification"
    description = "B7 Hero TTL/single-flight, third-party cancel/deadline, focus, HDR and Compose search continuation assertions."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.B7Checks")
}
verifyOffline.configure { dependsOn(b7Checks) }
val b9Checks = tasks.register<JavaExec>("b9Checks") {
    group = "verification"
    description = "B9 boot capability, exit traces, crash persistence, overlay and bounded process log assertions."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.B9Checks")
}
verifyOffline.configure { dependsOn(b9Checks) }
val b10Checks = tasks.register<JavaExec>("b10Checks") {
    group = "verification"
    description = "B10 pairing delivery, diagnostic export privacy/format/share and HTTP classification checks."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.B10Checks")
}
verifyOffline.configure { dependsOn(b10Checks) }
val b11Checks = tasks.register<JavaExec>("b11Checks") {
    group = "verification"
    description = "B11 Home totals, lifecycle work, retryable caches and playback hint regressions."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.B11Checks")
}
verifyOffline.configure { dependsOn(b11Checks) }
val b12Checks = tasks.register<JavaExec>("b12Checks") {
    group = "verification"
    description = "B12 extracted playback policy and legacy adapter boundary assertions."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.player.B12Checks")
}
verifyOffline.configure { dependsOn(b12Checks) }
val b8Checks = tasks.register<JavaExec>("b8Checks") {
    group = "verification"
    description = "B8 login deadline, search append errors/ranking, and update timeout assertions."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.B8Checks")
}
verifyOffline.configure { dependsOn(b8Checks) }
val e6VttChecks = tasks.register<JavaExec>("e6VttChecks") {
    group = "verification"
    description = "E6 optional VTT parsing, size limit, and failure downgrade assertions."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.player.E6VttChecks")
}
verifyOffline.configure { dependsOn(e6VttChecks) }
val b13Checks = tasks.register<JavaExec>("b13Checks") {
    group = "verification"
    description = "B13 cross-batch self-review regression assertions."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.B13Checks")
}
verifyOffline.configure { dependsOn(b13Checks) }
val uChecks = tasks.register<JavaExec>("uChecks") {
    group = "verification"
    description = "B14 device feedback regression assertions."
    dependsOn(samples)
    classpath = samples.get().classpath
    mainClass.set("com.lumina.tv.UChecks")
}
verifyOffline.configure { dependsOn(uChecks) }
tasks.matching { it.name in setOf("check", "assemble", "assembleDebug", "assembleRelease", "bundle", "bundleDebug", "bundleRelease") }
    .configureEach { dependsOn(verifyOffline) }
