import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 发布签名口令的读取顺序:keystore.properties(本地文件,已被 .gitignore 忽略)
// > gradle 属性 > 环境变量。口令不进仓库,避免密钥泄露后被伪造出可覆盖安装的升级包。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingSecret(key: String): String =
    keystoreProps.getProperty(key)
        ?: (project.findProperty(key) as String?)
        ?: System.getenv(key)
        ?: ""

android {
    namespace = "com.yanzhong.app"
    compileSdk = 34

    signingConfigs {
        // 发布签名(本地 keystore;口令来自 keystore.properties,该文件不进仓库。
        // 应用内更新覆盖安装要求签名一致,所以口令丢了就只能卸载重装。)
        create("release") {
            storeFile = file("yanzhong-release.jks")
            storePassword = signingSecret("YANZHONG_STORE_PASS")
            keyAlias = "yanzhong"
            keyPassword = signingSecret("YANZHONG_KEY_PASS")
        }
    }

    defaultConfig {
        applicationId = "com.yanzhong.app"
        minSdk = 29
        targetSdk = 34
        // OTA 用 versionCode 递增判定(服务端 current < latest),发布新包必须 +1,
        // 否则老设备永远收不到更新(updateAvailable 恒为 false)。
        versionCode = 2
        versionName = "1.0.0-m2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.compose.icons.lucide)
    // 云同步网络层:Retrofit + OkHttp + kotlinx-serialization
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
