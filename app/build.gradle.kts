import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 发布签名凭据的探测位置（按顺序取第一个存在的）。
 *
 * - `keystore/` —— 交接文档里约定的目录名
 * - `【私密】keystore/` —— 本仓库实际使用的目录名
 *
 * 两个都不存在时整体跳过签名：这样没有密钥的机器上克隆源码后，
 * debug 构建与单元测试照常能跑，不会因为缺密钥而整个构建失败。
 */
val keystorePropsFile: File? = listOf("keystore", "【私密】keystore")
    .map { File(rootProject.projectDir, it) }
    .map { File(it, "keystore.properties") }
    .firstOrNull { it.exists() }

android {
    namespace = "com.fxsimulator"
    compileSdk = 35
    // 本机为 aarch64：build-tools 只用到与架构无关的 jar，aapt2 由全局 override 提供
    buildToolsVersion = "35.0.0"

    defaultConfig {
        // 注意：applicationId（装到手机上的身份）与 namespace（代码包名）是两回事。
        // 这里只改 identity，namespace 保持 com.goldlab —— 64 个 package 声明、
        // 242 个 import、R 类位置全部不用动。上架之后这个值就再也不能改了。
        applicationId = "com.fxsimulator.app"
        minSdk = 26
        targetSdk = 35
        // applicationId 换成了新包名，等于一个全新应用，版本号从头计。
        // 从现在起每次发布 versionCode 都必须递增，否则商店会拒绝更新。
        // versionCode 必须严格递增（同一个 applicationId 下），否则商店会拒绝更新；
        // versionName 是自由文本，随便写。
        versionCode = 8
        versionName = "V-0.0.8"

    }

    /**
     * 发布签名。
     *
     * 凭据从 [keystorePropsFile] 读（`keystore/` 或 `【私密】keystore/`，见文件顶部）。
     * **不要提交到公开仓库。**
     */
    signingConfigs {
        if (keystorePropsFile != null) {
            create("release") {
                val p = Properties().apply {
                    keystorePropsFile.inputStream().use { load(it) }
                }
                // storeFile 按「凭据文件所在目录」解析：
                // properties 里写 keystore/release.jks 或 release.jks 都能命中同一份密钥。
                storeFile = File(keystorePropsFile.parentFile, p.getProperty("storeFile").substringAfterLast('/'))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 暂不开启混淆（见 proguard-rules.pro 的说明）。
            // 开启前先确认崩溃堆栈还能定位 —— 上次那个 CandleChart.kt:229 的崩溃就是靠行号定的位。
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// 允许 -Dgoldlab.genMarket=1 透传到测试 JVM：
// 内置行情资产（assets/market/*.glb1）由 Glb1RoundTripTest 从 market-data/*.csv 生成，
// 平时只做校验，只有显式带上这个参数才写文件。
tasks.withType<Test>().configureEach {
    systemProperty("goldlab.genMarket", System.getProperty("goldlab.genMarket", "0"))
    // Binance CSV 导入器的源目录（不传则导入测试自动跳过）
    systemProperty("goldlab.importBinance", System.getProperty("goldlab.importBinance", ""))
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

