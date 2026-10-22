pluginManagement {
    repositories {
        // 华为云镜像：本机实测约 4MB/s，google()/mavenCentral() 仅约 0.1MB/s
        maven("https://mirrors.huaweicloud.com/repository/maven/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.huaweicloud.com/repository/maven/")
        google()
        mavenCentral()
    }
}

rootProject.name = "GoldLab"
include(":app")
