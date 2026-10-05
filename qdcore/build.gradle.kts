import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Núcleo del protocolo QDLink/SSPLink (lado teléfono), copiado de qdauto/core (ver UPSTREAM.md).
// Kotlin/JVM puro, sin Android: lo usan :app y :qdsim. Bytecode Java 8, como el resto del fork.
plugins {
    kotlin("jvm") // KGP 1.9.22, ya en el classpath del buildscript raíz
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        // all: los métodos con cuerpo de las interfaces (SessionListener, PhoneLinkListener, Callback) son métodos
        // por defecto de la JVM → Java y Kotlin 1.9 pueden implementarlas en parte.
        // jdk-release: compila contra la API de Java 8 (atrapa String.repeat, java.time de Java 9+, etc.).
        freeCompilerArgs.addAll("-Xjvm-default=all", "-Xjdk-release=1.8")
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
