rootProject.name = "javassist-agent"

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        maven { setUrl("https://maven.aliyun.com/repository/central") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/spring") }
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal() // ✅ 官方插件仓库，Shadow 插件就在这里
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}



