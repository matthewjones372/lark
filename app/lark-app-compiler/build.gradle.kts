// The compiler is on the compile classpath only: it is already there when the plugin runs, and a
// published dependency on it would put a second copy of the Kotlin compiler in every consumer.
//
// The version comes from the root rather than a number written here: a plugin compiled against one
// compiler and loaded into another fails at a consumer's compile, or quietly in their editor.
dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:${rootProject.extra["larkKotlinVersion"]}")
    // A test runs outside the compiler, so it has to bring one; `compileOnly` does not reach here.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${rootProject.extra["larkKotlinVersion"]}")
}

// What this was built against, written into the jar so the checker can compare it with the compiler
// it finds itself in. A plugin loaded into a compiler it was not built for is the failure this
// module keeps finding new ways to have, and the editor's version of it is silent.
val builtFor = tasks.register<WriteProperties>("builtFor") {
    destinationFile.set(layout.buildDirectory.file("generated/lark/lark-compiler.properties"))
    property("kotlin", rootProject.extra["larkKotlinVersion"].toString())
}

sourceSets.main {
    resources.srcDir(builtFor.map { it.destinationFile.get().asFile.parentFile })
}
