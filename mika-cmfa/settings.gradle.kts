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
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }
}
rootProject.name = "Mika"
include(":core", ":app")
include(":cmfa-hideapi", ":cmfa-common", ":cmfa-core", ":cmfa-service", ":cmfa-design", ":cmfa-app")
