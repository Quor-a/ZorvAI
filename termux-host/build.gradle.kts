import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ai.assistance.quro.termux"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    buildFeatures {
        // termux-host 使用经典 View 体系（Activity/DrawerLayout/ViewPager），无需 Compose
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.annotation)
    implementation(libs.material)
    // DrawerLayout + ViewPager + Preference：Termux 宿主 UI 基础
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.viewpager:viewpager:1.0.0")
    implementation("androidx.preference:preference:1.2.1")
    // vtlib — 完整 VT 终端模拟器/视图（去品牌化 terminal-emulator + terminal-view）
    implementation(project(":vtlib"))
    // 终端核心：proot 启动参数 / rootfs 准备 / 构建 ProotLaunch
    implementation(project(":terminal-core"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
}