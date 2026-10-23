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
        // MPAndroidChart is only published on JitPack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "NutriLensAI"
include(":app")
