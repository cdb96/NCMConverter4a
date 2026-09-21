package com.cdb96.ncmconverter4a

import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopOutputAllocatorTest {
    @Test
    fun existingOutputExplainsConflictAndPreservesOriginal() {
        val directory = Files.createTempDirectory("ncm-existing-output-test").toFile()
        val existing = directory.resolve("song.mp3").apply { writeText("original audio") }
        try {
            val allocator = DesktopOutputAllocator(directory)
            val error = assertFailsWith<DesktopOutputExistsException> {
                allocator.withUniqueOutput("song", "mp3", false) {
                    error("Conversion must not start when the destination exists")
                }
            }
            assertTrue(error.message.orEmpty().contains(existing.absolutePath))
            assertTrue(error.message.orEmpty().contains("重名文件冲突缓解"))
            assertEquals("original audio", existing.readText())

            val (newFile, _) = allocator.withUniqueOutput("song", "mp3", true) {
                it.write("new audio".toByteArray())
            }
            assertEquals("song (1).mp3", newFile.name)
            assertEquals("new audio", newFile.readText())
            assertEquals("original audio", existing.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun concurrentReservationsGetDistinctSequentialNames() {
        val directory = Files.createTempDirectory("ncm-output-test").toFile()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val allocator = DesktopOutputAllocator(directory)
            val futures = (0 until 8).map {
                executor.submit(Callable {
                    val reserved = allocator.openUniqueOutput("test", "mp3", true)
                    reserved.stream.use { it.write(byteArrayOf(1, 2, 3)) }
                    reserved.file.name
                })
            }
            val names = futures.map { it.get() }.toSet()

            assertEquals(
                (0 until 8).map { index ->
                    if (index == 0) "test.mp3" else "test ($index).mp3"
                }.toSet(),
                names
            )
        } finally {
            executor.shutdownNow()
            Files.walk(directory.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder<java.nio.file.Path>())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
