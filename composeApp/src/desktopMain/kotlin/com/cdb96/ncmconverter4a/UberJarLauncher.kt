package com.cdb96.ncmconverter4a

import java.io.File
import kotlin.system.exitProcess

/**
 * The Compose Uber Jar has a normal Java manifest, which cannot encode
 * --add-modules. Relaunch it once with the Vector API module resolved so that
 * `java -jar` behaves the same as the native distribution launcher.
 */
fun main(args: Array<String>) {
    if (ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent) {
        Class.forName("com.cdb96.ncmconverter4a.MainKt")
            .getMethod("main")
            .invoke(null)
        return
    }

    val jarFile = File(UberJarLauncher::class.java.protectionDomain.codeSource.location.toURI())
    require(jarFile.isFile) { "Uber Jar launcher must run from a jar file" }

    val javaExecutable = File(
        File(System.getProperty("java.home"), "bin"),
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        }
    )
    val command = buildList {
        add(javaExecutable.absolutePath)
        add("--add-modules=jdk.incubator.vector")
        add("-jar")
        add(jarFile.absolutePath)
        addAll(args)
    }
    val exitCode = ProcessBuilder(command).inheritIO().start().waitFor()
    exitProcess(exitCode)
}

private object UberJarLauncher
