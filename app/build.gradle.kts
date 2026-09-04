import java.io.File
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ============ Release 签名配置（双通道，密钥绝不入库） ============
// 通道 1：本地开发 —— 项目根目录放 keystore.properties（已被 .gitignore 忽略）：
//   storeFile=./release.keystore
//   storePassword=xxx
//   keyAlias=xxx
//   keyPassword=xxx
// 通道 2：CI —— GitHub Actions Secrets（见 .github/workflows/build-apk.yml）：
//   KEYSTORE_BASE64（keystore 文件 base64）/ KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(name)?.takeIf { it.isNotBlank() }

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
        versionCode = 1
        versionName = "1.0.0"
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
