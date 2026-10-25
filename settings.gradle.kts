pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral()
  maven("https://repo.opencollab.dev/maven-releases/"); maven("https://repo.opencollab.dev/maven-snapshots/") } }
rootProject.name = "LunarInjector"
include(":app")
