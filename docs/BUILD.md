# FX模拟器 · 项目交接说明

## 这是什么

一个**纯离线**的黄金/CFD 历史行情模拟交易游戏，Android 原生应用。

| 项 | 值 |
|---|---|
| 包名（applicationId） | `com.fxsimulator.app` |
| 代码包名（namespace） | `com.fxsimulator` |
| 版本 | V-0.04（versionCode 6） |
| 技术栈 | Kotlin + Jetpack Compose，**零第三方依赖** |
| 网络权限 | **未申请**（不联网、不采集、不上传） |
| 内置数据 | 33 个月真实 XAUUSD M1 行情，约 92 万根 |
| release APK | 13.38 MB |

## 目录说明

| 目录 / 文件 | 说明 |
|---|---|
| `app/` | 应用源码（Kotlin + Compose + res 资源 + assets 内置行情） |
| `market-data/` | **源头数据**：33 个月的 MT5 导出 CSV（68 MB，不进 APK） |
| `tools/` | 图标生成脚本（纯 Python 标准库，不需要 PIL） |
| `keystore/` | **【私密】发布签名密钥**，见下文 |
| `build.gradle.kts` / `settings.gradle.kts` / `gradle/` | 构建配置与 wrapper |
| `FX模拟器-交付报告.md` | **完整技术记录**，含每次改动的原因与踩过的坑 |
| `FX模拟器-V-0.04-release.apk` | 已签名的发布包，可直接安装 |

## 构建

### 环境要求
- JDK 17 或 21
- Android SDK：platform-35 + build-tools 35.0.0

### ⚠️ 必看：aapt2 那一行

`gradle.properties` 里有这么一行：

```
android.aapt2FromMavenOverride=/opt/goldlab-tools/bin/aapt2
```

这是**原开发容器**的特殊配置 —— 那台机器是 aarch64，Google 官方不发布
linux-aarch64 版 aapt2，只能借用 Termux 的 aarch64 版本并手工补 bionic 依赖。

**在你的笔记本上（x86_64 或有官方工具链的机器），请把这一行删掉或注释掉**，
否则构建会因为找不到 aapt2 而失败。删掉之后 Gradle 会自动用 Maven 上的官方版本。

### local.properties

压缩包里**故意不放** `local.properties` —— 那个文件记录的是本机 SDK 路径
（原开发容器是 `/opt/goldlab-tools/android-sdk`），拿到别的机器上只会让构建失败。

用 Android Studio 打开项目时会自动生成；用命令行构建就自己建一个：

```
sdk.dir=/你的/Android/sdk/路径
```

### 构建命令

```bash
./gradlew assembleRelease          # 发布包（会自动签名）
./gradlew assembleDebug            # 调试包
./gradlew testDebugUnitTest        # 208 个单元测试
```

产物在 `app/build/outputs/apk/release/app-release.apk`。

## 签名

`keystore/release.jks` + `keystore/keystore.properties`（含口令）。

`build.gradle.kts` 里配置为：这两个文件存在就自动签名，**不存在则跳过** ——
所以即使只拿到源码没有密钥，debug 构建和单元测试仍然能跑。

> **这是整个项目里唯一不可逆的东西。** 丢了它，这个应用永远无法再发布新版本
> （商店会以签名不一致拒绝，已装用户也收不到更新）。请另外备份到网盘/邮箱。

证书指纹（备案、商店会用到）：

```
SHA-256: 32613a1bde0560d8af26dc9a5c2986d536e278982c6f8993e4b5f2cd7bbf7638
SHA-1  : da2857df420d988eac678590a89dcda5e434a0b9
```

## 行情数据

两种形态，用途不同：

- `market-data/*.csv` —— 源头数据（MT5 导出），**不进 APK**
- `app/src/main/assets/market/*.glb1` —— 打包用的**自研列式二进制**，11.38 MB

改成 GLB1 之后 APK 里的资产从 18.83 MB 降到 6.71 MB，单月加载从 161 ms 降到 2 ms。
格式细节与单位约定见交付报告里 0.5.0 那一章。

改完 CSV 后重新生成 GLB1：

```bash
./gradlew testDebugUnitTest -Dgoldlab.genMarket=1 \
    --tests com.fxsimulator.Glb1RoundTripTest
```

不带 `-Dgoldlab.genMarket=1` 时，这个测试只做**校验**（逐根逐字段比对 CSV 与 GLB1），
不会写文件。

## 图标

```bash
python3 tools/icon_fx.py app/src/main/res
```

纯 Python 标准库实现（PNG 解码、缩放、遮罩全部手写），不需要 PIL / ImageMagick。
字形是几何拼出来的，不依赖字体。改配色或字号直接改脚本顶部的常量。

## 数据来源与许可

行情数据由项目所有者从本人的 Exness MT5 模拟账户导出。
Exness 官方客服明确答复：可以导出、可以分发给第三方、不限制用途。

> **注意**：该书面答复记录**未包含在本压缩包内**，请项目所有者自行保存。
> 若将来有人主张数据权利，这份记录是第一手依据。
