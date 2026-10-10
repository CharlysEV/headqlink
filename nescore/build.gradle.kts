// Núcleo del emulador NES: halfNES (Andrew Hoffman, GPL-3.0, https://github.com/andrew-hoffman/halfnes), sin sus partes
// de escritorio (Swing, AWT, JavaFX, JInput). Java puro, sin Android: :app pone la salida de audio y el dibujo.
// Se combina con HeadQLink (AGPL-3.0) según la sección 13 de ambas licencias; ver UPSTREAM.md y NOTICE.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    // El código viene tal cual de halfNES: sus avisos no son nuestros.
    options.compilerArgs.add("-nowarn")
}
