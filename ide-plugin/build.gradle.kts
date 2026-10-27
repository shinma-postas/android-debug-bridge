plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.asdbg"
version = "0.1.9"

/**
 * Android Studio のインストール先を決める。
 * 1. Gradle プロパティ androidStudioPath（-PandroidStudioPath=... か ~/.gradle/gradle.properties）
 * 2. 環境変数 ANDROID_STUDIO_PATH
 * 3. OS ごとのよくある場所
 * macOS は .app のパス、Linux と Windows は bin と lib があるディレクトリを指す。
 */
fun findAndroidStudio(): File {
    val explicit = (findProperty("androidStudioPath") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_STUDIO_PATH")?.takeIf { it.isNotBlank() }
    if (explicit != null) {
        val dir = File(explicit)
        require(dir.isDirectory) { "androidStudioPath / ANDROID_STUDIO_PATH が見つからない: $explicit" }
        return dir
    }
    val home = System.getProperty("user.home")
    val candidates = listOf(
        "/Applications/Android Studio.app",
        "$home/Applications/Android Studio.app",
        "/opt/android-studio",
        "/usr/local/android-studio",
        "$home/android-studio",
        "/snap/android-studio/current/android-studio",
        "C:\\Program Files\\Android\\Android Studio",
        "${System.getenv("LOCALAPPDATA") ?: ""}\\Programs\\Android Studio",
    )
    return candidates.map(::File).firstOrNull { it.isDirectory }
        ?: throw GradleException(
            "Android Studio が見つからない。-PandroidStudioPath=<path> か環境変数 ANDROID_STUDIO_PATH で指定する。探した場所: $candidates",
        )
}

val androidStudio = findAndroidStudio()
// macOS は Contents/lib、Linux と Windows は lib。
val studioLib = File(androidStudio, "Contents/lib").takeIf { it.isDirectory } ?: File(androidStudio, "lib")
val gsonJar = File(studioLib, "intellij.libraries.gson.jar")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(androidStudio.path)
        bundledPlugin("org.jetbrains.android")
    }
    compileOnly(files(gsonJar))
    testImplementation("junit:junit:4.13.2")
    testImplementation(files(gsonJar))
}

tasks.test {
    systemProperty("asdbg.exampleOut", layout.buildDirectory.file("asdbg-snapshot-example.json").get().asFile.path)
    systemProperty("asdbg.webhookExampleOut", layout.buildDirectory.file("asdbg-webhook-example.json").get().asFile.path)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.asdbg.bridge"
        name = "Android Debug Bridge"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262.9437"
            untilBuild = "262.*"
        }
    }
    buildSearchableOptions = false
}
