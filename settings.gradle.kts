pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            version("constraintlayout", "2.1.4")

            library("constraintlayout", "androidx.constraintlayout", "constraintlayout").versionRef("constraintlayout")
        }
    }
}

rootProject.name = "RWR1"
include(":app")
 