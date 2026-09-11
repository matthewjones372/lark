// The compiler is on the compile classpath only: it is already there when the plugin runs, and a
// published dependency on it would put a second copy of the Kotlin compiler in every consumer.
dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
}
