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
    }
}
rootProject.name = "phoenix-field-camera"
include(":app")
// The rep tablets' own Phoenix Position agent: a second, separate APK, from the same repository and the same key.
include(":position")
