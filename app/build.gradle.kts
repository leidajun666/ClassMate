import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 从 keystore.properties 读取签名信息；仓库里不含密钥，文件缺失则跳过签名。
val signProp = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigningKey = signProp.getProperty("storeFile") != null

android {
    namespace = "com.classmate.timetable"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.classmate.timetable"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.2.1"
        resourceConfigurations += listOf("zh", "en")
    }

    // 签名信息放 keystore.properties（已 gitignore）。文件不存在时不配置签名，
    // 保证任何人 clone 之后都能直接 assembleDebug，而不用改代码。
    signingConfigs {
        if (hasSigningKey) {
            create("personal") {
                storeFile = file(signProp.getProperty("storeFile", "keystore/release.jks"))
                storePassword = signProp.getProperty("storePassword", "")
                keyAlias = signProp.getProperty("keyAlias", "")
                keyPassword = signProp.getProperty("keyPassword", "")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            if (hasSigningKey) signingConfig = signingConfigs.getByName("personal")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasSigningKey) signingConfig = signingConfigs.getByName("personal")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.core:core-splashscreen:1.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
