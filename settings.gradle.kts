pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libausbc (jiangdongguo/AndroidUSBCamera) — the proven UVC camera engine.
        maven { url = uri("https://jitpack.io") }
        // libausbc pulls com.gyf.immersionbar:immersionbar which only ever lived on
        // JCenter; these are its mirrors.
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://jcenter.bintray.com") }
    }
}

rootProject.name = "AndroidUsbCameraHud"
include(":app")
