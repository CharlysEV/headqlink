import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Escenarios de prueba en el PC contra el móvil (qdauto §9.3): CarSim de :qdcore haciendo de C10 (reconexión, cortes de
// radio, relevo, DISCONNECT_REQ, ACK perdido). Uso: .\gradlew.bat :qdsim:run --args="--scenario reconnect --sessions 10"
plugins {
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll("-Xjvm-default=all", "-Xjdk-release=1.8")
    }
}

dependencies {
    implementation(project(":qdcore"))
}

application {
    mainClass.set("dev.qdauto.qdsim.QdSimKt")
    applicationName = "qdsim"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}
