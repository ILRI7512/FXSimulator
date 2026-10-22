// GoldLab 根构建脚本：只声明插件版本，不在此处配置业务
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // 写 "17.1" 会解析不到（这一步我踩过）。
}
