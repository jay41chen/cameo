plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

fun resolveBaseUrl(raw: String?): String {
    if (raw == null) return "http://10.0.2.2:8080"
    val match = Regex("""http://10\.0\.2\.2:([1-9][0-9]{0,4})/?""").matchEntire(raw)
    val port = match?.groupValues?.get(1)?.toInt()
    if (port == null || port > 65535) {
        throw GradleException(
            """
            cameoBaseUrl 格式錯誤：收到 "$raw"。
            格式：http://10.0.2.2:<port>（port 為 1–65535，不可有前導零，結尾可有一個 /）
            範例：./gradlew :app:assembleDebug -PcameoBaseUrl=http://10.0.2.2:9090
            """.trimIndent(),
        )
    }
    return "http://10.0.2.2:$port"
}

android {
    namespace = "io.github.jay41chen.cameo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.jay41chen.cameo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2"

        // 後端網址預設 http://10.0.2.2:8080，用 -PcameoBaseUrl 覆蓋；
        // 格式與規則詳見 spec-mobile.md 第 6 章「後端網址」。測試帳號 token 為模擬環境值。
        buildConfigField("String", "BASE_URL", "\"${resolveBaseUrl(providers.gradleProperty("cameoBaseUrl").orNull)}\"")
        buildConfigField("String", "API_TOKEN", "\"test-token\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
