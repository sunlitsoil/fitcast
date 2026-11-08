plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("ai.fitcast.core.SimulatorKt")
}

tasks.test { useJUnitPlatform() }
