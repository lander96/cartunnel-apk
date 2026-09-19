plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.cartunnel.client"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.cartunnel.client"
        minSdk = 28
        targetSdk = 36
        versionCode = 12
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { buildConfig = true }
    sourceSets { getByName("main").assets.srcDir("../licenses") }
    val userDebugKeystore = rootProject.file(".cache/android-user/debug.keystore")
    require(userDebugKeystore.isFile) {
        "Required debug keystore is missing: ${userDebugKeystore.absolutePath}; refusing to create a replacement key"
    }
    signingConfigs {
        create("userDebug") {
            storeFile = userDebugKeystore
            storePassword = providers.gradleProperty("debugKeystorePassword").orElse("android").get()
            keyAlias = "androiddebugkey"
            keyPassword = providers.gradleProperty("debugKeyPassword").orElse("android").get()
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("userDebug")
        }
        release {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("userDebug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("previewRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-test"
            signingConfig = signingConfigs.getByName("userDebug")
            matchingFallbacks += listOf("release")
        }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    lint { abortOnError = true; checkReleaseBuilds = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(files("../core-xray/libs/cartunnel-xray-slim.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}

dependencyLocking {
    lockAllConfigurations()
}
