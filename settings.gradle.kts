@file:Suppress("ktlint:standard:kdoc")

pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("kei") {
            from(files("gradle/kei.versions.toml"))
        }
    }
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Keiyoushi"

/**
 * Add or remove modules to load as needed for local development here.
 */
// Only load the Comix extension (all other extensions have been removed from this fork).
// To add more extensions later, either use loadIndividualExtension("lang", "name") for each,
// or switch back to loadAllIndividualExtensions() to auto-discover everything under src/.
loadIndividualExtension("all", "comixto")
loadIndividualExtension("all", "manhuarmtl")
loadIndividualExtension("all", "swarm")
loadIndividualExtension("en", "kagane")
loadIndividualExtension("en", "atsumaru")
loadIndividualExtension("en", "mkissa")
loadIndividualExtension("all", "mangaball")
loadIndividualExtension("en", "weebcentral")
loadIndividualExtension("all", "xcomic")
loadIndividualExtension("all", "mangadot")
loadIndividualExtension("en", "mangataro")
loadIndividualExtension("en", "toonz")
loadIndividualExtension("en", "mangago")
loadIndividualExtension("all", "mangafire")
loadIndividualExtension("ar", "mangatek")
loadIndividualExtension("ar", "procomic")
loadIndividualExtension("ar", "mangatime")
loadIndividualExtension("ar", "mangadar")
loadIndividualExtension("ar", "kawaiimanga")
loadIndividualExtension("ar", "mangalek")
loadIndividualExtension("ar", "hijala")
loadIndividualExtension("ar", "azora")
loadIndividualExtension("ar", "teamx")
loadIndividualExtension("ar", "mangaswat")
// loadAllIndividualExtensions()

/**
 * ===================================== COMMON CONFIGURATION ======================================
 */
include(":core")
include(":compiler")

// Load all modules under /lib
File(rootDir, "lib").eachDir { include("lib:${it.name}") }

// Load all modules under /lib-multisrc
File(rootDir, "lib-multisrc").eachDir { include("lib-multisrc:${it.name}") }

/**
 * ======================================== HELPER FUNCTION ========================================
 */
fun loadAllIndividualExtensions() {
    File(rootDir, "src").eachDir { dir ->
        dir.eachDir { subdir ->
            include("src:${dir.name}:${subdir.name}")
        }
    }
}
fun loadIndividualExtension(lang: String, name: String) {
    // v104: existence-guarded — upstream prunes dead extensions from its
    // tree (kagane was removed from HEAD), and an unconditional include
    // makes any checkout that tracks the template fail to configure.
    if (File(rootDir, "src/$lang/$name").exists()) include("src:$lang:$name")
}

fun File.eachDir(block: (File) -> Unit) {
    val files = listFiles() ?: return
    for (file in files) {
        if (file.isDirectory && file.name != ".gradle" && file.name != "build") {
            block(file)
        }
    }
}
