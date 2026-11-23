// The app's own Stratum.kt, Work.kt and Texts.kt (no Android in them) and a harness around them, on the JVM.
plugins {
    kotlin("jvm") version "2.4.21"
    application
}

dependencies {
    // Android has org.json built in; on a computer it comes from here.
    implementation("org.json:json:20240303")
}

kotlin {
    sourceSets["main"].kotlin.srcDir("../../app/src/main/java")
}

sourceSets["main"].kotlin {
    include("Harness.kt", "org/polarka/miner/Stratum.kt", "org/polarka/miner/Work.kt", "org/polarka/miner/Texts.kt")
}

application {
    mainClass.set("HarnessKt")
}
