plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.codecanvas.core"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    api(libs.androidx.core.ktx)
    api(libs.coroutines.android)
    // core 的公开 API 签名里出现 ImageBitmap（RenderOutput.ComposeBitmap / Exporter.bitmapOf），
    // 下游模块编译时必须能看到该类型，故用 api 而不是 compileOnly
    //（compileOnly 只对本模块编译可见，下游会报 Unresolved reference 'compose'）。
    // 运行时不需要 core 自带 Compose：app 已依赖 Compose，renderer-compose 也会带入。
    api(platform(libs.compose.bom))
    api(libs.compose.ui.graphics)
}
