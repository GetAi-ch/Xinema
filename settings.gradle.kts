pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Xinema"

// Setiap folder provider dengan build.gradle.kts otomatis menjadi modul.
// Pola ini mengikuti repository nonton-indo dan menghindari daftar modul
// manual yang sering tertinggal saat provider baru ditambahkan.
rootDir.listFiles()
    ?.filter { dir ->
        dir.isDirectory &&
            !dir.name.startsWith(".") &&
            File(dir, "build.gradle.kts").isFile
    }
    ?.sortedBy { it.name.lowercase() }
    ?.forEach { dir ->
        include(":" + dir.name)
    }
