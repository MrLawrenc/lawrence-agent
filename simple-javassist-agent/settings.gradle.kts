rootProject.name = "simple-javassist-agent"

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
        maven { setUrl("https://maven.aliyun.com/repository/public") }
    }
}
