// The compiler is on the compile classpath only: it is already there when the plugin runs, and a
// published dependency on it would put a second copy of the Kotlin compiler in every consumer.
//
// The version comes from the root rather than a number written here: a plugin compiled against one
// compiler and loaded into another fails at a consumer's compile, or quietly in their editor.
dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:${rootProject.extra["larkKotlinVersion"]}")
}
