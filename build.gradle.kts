group = "me.yellowhead"
version = "0.9.0-beta-163"

plugins {
    kotlin("jvm") version "2.0.21"
    id("com.typewritermc.module-plugin") version "1.3.0"
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
}

typewriter {
    namespace = "yellowhead"

    extension {
        name = "PlayerInputAudiences"
        shortDescription = "Detect and filter players based on key inputs"
        description = "Provides audience filters for detecting player inputs in real-time, including movement (W/A/S/D) and actions (jump, sneak, sprint).Useful for custom mechanics, AFK detection, or gameplay triggers."
        engineVersion = "0.9.0-beta-163"
        channel = com.typewritermc.moduleplugin.ReleaseChannel.BETA

        paper {
            // You can put Paper-specific config here if needed
        }
    }
}

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

configurations.all {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-stdlib:2.0.21",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.21",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.21",
            "org.jetbrains.kotlin:kotlin-reflect:2.0.21"
        )
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.0.21"))

}

kotlin {
    jvmToolchain(21)
}
