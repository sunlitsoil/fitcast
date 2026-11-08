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
        // Pleos Connect SDK 공개 Maven 저장소 (공식 문서 「Importing Vehicle SDK」)
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}

rootProject.name = "fitcast"
include(":core")
// 안드로이드 SDK가 없는 환경에서는 core만 빌드한다: ./gradlew -PcoreOnly :core:test
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
