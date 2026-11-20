// 仓库顺序：官方源优先，阿里云镜像作为回退。
// 容器/CI 环境访问 dl.google.com 通常比镜像更稳定；镜像在部分网络下
// 会出现 TLS 握手中断（同一 URL 用 curl 可以下载，Gradle 却失败）。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
    }
}

rootProject.name = "VntApp"
include(":app")
