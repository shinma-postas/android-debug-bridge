plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.asdbg"
version = "0.1.5"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("/Applications/Android Studio.app")
        bundledPlugin("org.jetbrains.android")
    }
    compileOnly(files("/Applications/Android Studio.app/Contents/lib/intellij.libraries.gson.jar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(files("/Applications/Android Studio.app/Contents/lib/intellij.libraries.gson.jar"))
}

tasks.test {
    systemProperty("asdbg.exampleOut", System.getProperty("user.home") + "/Downloads/asdbg-snapshot-example.json")
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
        version = "0.1.5"
        ideaVersion {
            sinceBuild = "262.9437"
            untilBuild = "262.*"
        }
    }
    buildSearchableOptions = false
}
