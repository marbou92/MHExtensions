pluginManagement {
    repositories {
        // v115 recovery: the kotlin("jvm") plugin MARKER for build-logic
        // resolves from HERE (portal default 429-throttles this sandbox IP)
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://maven.aliyun.com/repository/google")
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        // v115 recovery: Aliyun first — Maven Central 429-throttles this
        // sandbox IP (same fix as the root settings/buildscript).
        // jitpack BEFORE central (404 falls through, 429 aborts).
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        google()
        maven(url = "https://www.jitpack.io")
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../libs.versions.toml"))
        }
        create("kei") {
            from(files("../kei.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
