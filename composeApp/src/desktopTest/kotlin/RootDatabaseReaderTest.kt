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

    private fun withFiles(test: (File, File) -> Unit) {
        val root = Files.createTempDirectory("kgg-root-reader").toFile()
        try {
            val cache = File(root, "cache").apply { mkdirs() }
            test(File(root, "database.mmkv"), cache)
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
        if (mode == "denied") System.err.println("cat: Permission denied")
        else repeat(4096) { System.err.println("su diagnostic warning") }
        System.out.write(Files.readAllBytes(Paths.get(args[1])))
        System.out.flush()
        if (mode == "denied") exitProcess(17)
    }
}
