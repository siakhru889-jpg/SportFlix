import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.compose") version "1.6.11"
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("uk.co.caprica:vlcj:4.8.2")          // видео: нужен установленный VLC (64-бит)
    implementation("org.json:json:20240303")
    implementation("net.sf.kxml:kxml2:2.3.0")           // разбор XMLTV (программа передач)
    implementation("xmlpull:xmlpull:1.1.3.1")
}

kotlin { jvmToolchain(17) }

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "SportflixIPTV"
            packageVersion = "1.0.0"
            description = "Sportflix IPTV для Windows"
            windows { menuGroup = "Sportflix IPTV"; shortcut = true; dirChooser = true }
        }
    }
}
