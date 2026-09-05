import java.io.File
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ============ Release 签名配置（双通道，密钥绝不入库） ============
// 通道 1：仓库内置 keystore —— keystore.properties（storeFile/storePassword/keyAlias/keyPassword）
// 通道 2：CI Secrets 覆盖 —— KEYSTORE_BASE64（keystore 文件 base64）/ KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
//   （Secrets 存在时优先于 keystore.properties，便于 fork 后自行更换密钥）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/** 取密钥配置：先读环境变量（CI Secrets），再回退 keystore.properties（含别名映射） */
fun secret(envName: String): String? {
    System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { return it }
    val propName = when (envName) {
        "KEYSTORE_PASSWORD" -> "storePassword"
        "KEY_ALIAS" -> "keyAlias"
        "KEY_PASSWORD" -> "keyPassword"
        else -> envName
    }
    return keystoreProps.getProperty(propName)?.takeIf { it.isNotBlank() }
}

val releaseStoreFile: File? = when {
    secret("KEYSTORE_BASE64") != null -> {
        val f = rootProject.layout.buildDirectory.get().asFile.resolve("keystores/release.jks")
        f.parentFile.mkdirs()
        f.writeBytes(Base64.getDecoder().decode(secret("KEYSTORE_BASE64")!!))
        f
    }
    keystoreProps.getProperty("storeFile")?.isNotBlank() == true ->
        rootProject.file(keystoreProps.getProperty("storeFile")!!)
    else -> null
}

val releaseStorePassword = secret("KEYSTORE_PASSWORD")
val releaseKeyAlias = secret("KEY_ALIAS")
val releaseKeyPassword = secret("KEY_PASSWORD") ?: releaseStorePassword
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null

android {
    namespace = "com.cgfree"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cgfree"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "1.0.14"

        // 只保留 arm64-v8a：当前项目无原生 so 依赖，此配置用于锁定目标架构，
        // 如需兼容 32 位设备请移除或追加 armeabi-v7a / x86_64
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        // debug 与 release 统一用仓库内置 keystore 签名（若存在 release 签名配置），
        // 保证本机构建与 GitHub Actions 产物签名一致，可直接覆盖安装；
        // 仓库未带 keystore（如 fork 后自行清理）时 debug 回退默认 debug key
        debug {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 未提供签名信息时自动退化为 unsigned release（CI 可结合 Secrets 产出签名包）
            signingConfig = signingConfigs.findByName("release")
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
