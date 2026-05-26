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
        // Rokid Maven (CXR-L SDK)
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        // Local AAR fallback (if Rokid maven access fails)
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "Constellation-Glass"
include(":app")
