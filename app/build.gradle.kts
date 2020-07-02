import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

val keystoreProps by lazy { loadProps("keystore.properties") }

android {
    compileSdkVersion(29)
    buildToolsVersion("30.0.0")

    defaultConfig {
        minSdkVersion(21)
        targetSdkVersion(29)

        applicationId = "com.tml.sharethem.demo"
        versionCode = 16
        versionName = "8.1"
    }

    signingConfigs {
//        create("release") {
//            keyAlias = keystoreProps.getProperty("keyAlias")
//            keyPassword = keystoreProps.getProperty("keyPassword")
//            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
//            storePassword = keystoreProps.getProperty("storePassword")
//        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
//            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
        }
    }

//    sourceSets["main"].java.srcDir("src/main/kotlin")

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.3.72")
    implementation("androidx.core:core-ktx:1.3.0")
    implementation("androidx.appcompat:appcompat:1.1.0")
    implementation("androidx.fragment:fragment:1.2.5")
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:1.1.3")

    implementation("com.google.code.gson:gson:2.8.5")
    implementation("com.github.angads25:filepicker:1.0.6")
}

fun loadProps(filename: String) = Properties().apply {
    load(rootProject.file(filename).inputStream())
}