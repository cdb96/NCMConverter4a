package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.kgg.KggBatchKeys
import com.cdb96.ncmconverter4a.converter.kgg.KggKeyDatabase
import com.cdb96.ncmconverter4a.converter.kgg.MMKVParser
import com.cdb96.ncmconverter4a.converter.kgg.root.RootDatabaseReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RootDatabaseReaderTest {
    @Test
    fun defaultReadUsesGlobalMountNamespace() = withFiles { source, cache ->
        val bytes = ByteArray(64) { it.toByte() }
        source.writeBytes(bytes)
        val commands = mutableListOf<List<String>>()
        val reader = RootDatabaseReader(cache) { arguments ->
            commands += arguments
            child("warning", source)
        }
        assertContentEquals(bytes, reader.read())
        assertEquals(1, commands.size)
        assertEquals(listOf("su", "-M", "-c"), commands.single().take(3))
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun unsupportedMountMasterFallsBackWithoutMixingOptionErrorIntoDatabase() = withFiles { source, cache ->
        val bytes = ByteArray(64) { (255 - it).toByte() }
        source.writeBytes(bytes)
        for (mode in listOf("unsupported-stdout", "unsupported-stderr")) {
            val commands = mutableListOf<List<String>>()
            val reader = RootDatabaseReader(cache) { arguments ->
                commands += arguments
                child(if ("-M" in arguments) mode else "warning", source)
            }
            assertContentEquals(bytes, reader.read())
            assertEquals(2, commands.size)
            assertEquals(listOf("su", "-c"), commands.last().take(2))
            assertEquals(commands.first().last(), commands.last().last())
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun deniedRootRequestDoesNotRetryWithoutMountMaster() = withFiles { source, cache ->
        source.writeBytes(ByteArray(64))
        var launches = 0
        val reader = RootDatabaseReader(cache) {
            launches++
            child("denied", source)
        }
        val error = assertFailsWith<IllegalStateException> { reader.read() }
        assertTrue(error.message.orEmpty().contains("Permission denied"))
        assertEquals(1, launches)
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun shellPrefersCurrentUserAndQuotesPathsWithSpacesAndApostrophes() = withFiles { source, cache ->
        val bytes = ByteArray(64) { it.toByte() }
        source.writeBytes(bytes)
        otherUserDatabase(cache, "0").writeBytes(ByteArray(64) { 99 })
        val reader = RootDatabaseReader(cache) { shell(it, cache) }
        assertContentEquals(bytes, reader.read())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun shellFindsMainUserOrCloneWhenCurrentUserHasNoDatabase() {
        for (user in listOf("0", "999")) withFiles { _, cache ->
            val bytes = ByteArray(64) { (it * 17).toByte() }
            otherUserDatabase(cache, user).writeBytes(bytes)
            val reader = RootDatabaseReader(cache) { shell(it, cache) }
            assertContentEquals(bytes, reader.read())
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun shellReportsInvisibleDatabaseSeparatelyFromIncompleteDatabase() = withFiles { source, cache ->
        val reader = RootDatabaseReader(cache) { shell(it, cache) }
        val missing = assertFailsWith<IllegalStateException> { reader.read() }
        assertTrue(missing.message.orEmpty().contains("exit=44"))
        assertTrue(missing.message.orEmpty().contains("未找到可见"))
        source.writeBytes(ByteArray(4))
        otherUserDatabase(cache, "0").writeBytes(ByteArray(64))
        val incomplete = assertFailsWith<IllegalStateException> { reader.read() }
        assertTrue(incomplete.message.orEmpty().contains("为空或不完整"))
        assertTrue(incomplete.message.orEmpty().contains(source.invariantSeparatorsPath))
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun successfulBinaryReadIgnoresLargeStderrAndRemovesTemporaryDiagnostics() = withFiles { source, cache ->
        val bytes = ByteArray(4096) { (it * 17).toByte() }
        source.writeBytes(bytes)
        val reader = RootDatabaseReader(cache)
        val command = child("warning", source).redirectErrorStream(true)
        assertContentEquals(bytes, reader.read(command))
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun nonzeroExitReportsPermissionFailureAndDiscardsPartialStdout() = withFiles { source, cache ->
        source.writeBytes(ByteArray(64) { 1 })
        val error = assertFailsWith<IllegalStateException> {
            RootDatabaseReader(cache).read(child("denied", source))
        }
        assertTrue(error.message.orEmpty().contains("exit=17"))
        assertTrue(error.message.orEmpty().contains("Permission denied"))
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun emptyOutputAndMissingExecutableFailWithCleanup() = withFiles { source, cache ->
        source.writeBytes(ByteArray(0))
        val empty = assertFailsWith<IllegalStateException> { RootDatabaseReader(cache).read(child("warning", source)) }
        assertTrue(empty.message.orEmpty().contains("为空或不完整"))
        val missing = assertFailsWith<IllegalStateException> {
            RootDatabaseReader(cache).read(ProcessBuilder(File(cache, "missing-su").absolutePath))
        }
        assertTrue(missing.message.orEmpty().contains("无法启动 su"))
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun parallelRootLookupsExecuteOneProcessForTheBatch() = withFiles { source, cache ->
        val ekey = "AQIDBAUGBwijvfhds1lCBVcHv7WluvwG".encodeToByteArray()
        val hash = "test-audio-hash".encodeToByteArray()
        source.writeBytes(ByteArray(8) + byteArrayOf(hash.size.toByte()) + hash +
            byteArrayOf(0x0A, 0, ekey.size.toByte()) + ekey)
        val launches = AtomicInteger()
        val keys = KggBatchKeys {
            launches.incrementAndGet()
            val parser = MMKVParser(RootDatabaseReader(cache).read(child("warning", source)))
            KggKeyDatabase(parser::getBytes)
        }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..32).map {
                pool.submit<ByteArray> {
                    start.await()
                    keys.getKey(hash.decodeToString())
                }
            }
            assertEquals(0, launches.get())
            start.countDown()
            results.forEach { assertContentEquals(ByteArray(14) { (it + 1).toByte() }, it.get(15, TimeUnit.SECONDS)) }
            assertEquals(1, launches.get())
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun child(mode: String, source: File): ProcessBuilder {
        val executable = File(System.getProperty("java.home"), "bin/java")
        val classpath = listOf(RootProcessFixture::class.java, Unit::class.java).joinToString(File.pathSeparator) {
            Paths.get(it.protectionDomain.codeSource.location.toURI()).toString()
        }
        return ProcessBuilder(executable.path, "-cp", classpath, RootProcessFixture::class.java.name, mode, source.path)
    }

    private fun otherUserDatabase(cache: File, user: String): File =
        File(cache.parentFile.parentFile, "users/$user/${RootDatabaseReader.DATABASE_PATH.substringAfter("/data/data/")}")
            .apply { parentFile.mkdirs() }

    private fun shell(arguments: List<String>, cache: File): ProcessBuilder {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) {
            File("C:/Program Files/Git/bin/bash.exe")
        } else File("/bin/sh")
        check(executable.isFile) { "Root shell regression tests require a POSIX shell: $executable" }
        // Run the production search script against a fake Android filesystem.
        val command = arguments.last()
            .replace("/data/user/", "users/")
            .replace("/data/data/", "legacy/")
        // Use a script file so Windows command-line escaping cannot change the
        // POSIX quotes we want to exercise on the JVM.
        val script = File(cache.parentFile.parentFile, "read-database.sh").apply { writeText(command) }
        return ProcessBuilder(executable.path, script.invariantSeparatorsPath).directory(cache.parentFile.parentFile)
    }

    private fun withFiles(test: (File, File) -> Unit) {
        val root = Files.createTempDirectory("kgg-root-reader space ' ").toFile()
        try {
            val cache = File(root, "com.cdb96.ncmconverter4a/cache").apply { mkdirs() }
            val source = File(root, RootDatabaseReader.DATABASE_PATH.substringAfter("/data/data/"))
                .apply { parentFile.mkdirs() }
            test(source, cache)
        } finally {
            root.deleteRecursively()
        }
    }
}

/** A real child process that simulates su/cat output without requiring Root. */
object RootProcessFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args[0]
        if (mode.startsWith("unsupported-")) {
            val output = if (mode == "unsupported-stdout") System.out else System.err
            output.println("su: unrecognized option '-M'")
            exitProcess(2)
        }
        if (mode == "denied") System.err.println("cat: Permission denied")
        else repeat(4096) { System.err.println("su diagnostic warning") }
        System.out.write(Files.readAllBytes(Paths.get(args[1])))
        System.out.flush()
        if (mode == "denied") exitProcess(17)
    }
}
