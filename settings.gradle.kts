pluginManagement {
    repositories {
        // Tier 1 — Iranian mirrors (domestic internet, no VPN)
        // On domestic internet these are fastest; from abroad they timeout and Gradle skips them.
        maven { url = uri("https://maven.myket.ir") }
        maven { url = uri("https://en-mirror.ir") }
        maven { url = uri("https://maven.devneeds.ir") }
        maven { url = uri("https://gradle.iranrepo.ir") }
        maven { url = uri("https://gradle.jamko.ir") }
        maven { url = uri("https://archive.ito.gov.ir/gradle/maven-plugin/") }
        // Tier 2 — Chinese mirrors (no sanctions, reachable from Iran without VPN)
        // Tencent has the AGP plugin marker; Aliyun mirrors gradle-plugin + google.
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        // Tier 3 — Official fallbacks (VPN / open internet)
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Tier 1 — Iranian mirrors
        maven { url = uri("https://maven.myket.ir") }
        maven { url = uri("https://en-mirror.ir") }
        maven { url = uri("https://maven.devneeds.ir") }
        maven { url = uri("https://gradle.iranrepo.ir") }
        maven { url = uri("https://gradle.jamko.ir") }
        maven { url = uri("https://archive.ito.gov.ir/gradle/maven_central/") }
        // Tier 2 — Chinese mirrors
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
        // Tier 3 — Official fallbacks
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Arya Messenger"
include(":app")
