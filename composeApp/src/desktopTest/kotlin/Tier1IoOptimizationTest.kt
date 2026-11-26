package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.KGMConverter
import com.cdb96.ncmconverter4a.converter.NCMConverter
import com.cdb96.ncmconverter4a.io.BinaryInput
import com.cdb96.ncmconverter4a.io.BinaryInputException
import com.cdb96.ncmconverter4a.io.BinaryOutput
import com.cdb96.ncmconverter4a.io.PassThroughBufferedOutputStream
import com.cdb96.ncmconverter4a.io.PrefetchInputStream
import com.cdb96.ncmconverter4a.io.readAtMost
import com.cdb96.ncmconverter4a.io.readChunk
import com.cdb96.ncmconverter4a.io.readFully
import com.cdb96.ncmconverter4a.io.skipFully
import com.cdb96.ncmconverter4a.jni.KGMDecrypt
import com.cdb96.ncmconverter4a.jni.RC4Decrypt
import com.cdb96.ncmconverter4a.service.ConversionFiles
import com.cdb96.ncmconverter4a.service.FileConversionService
import com.cdb96.ncmconverter4a.service.PendingOutput
import com.cdb96.ncmconverter4a.service.boundedWorkerBufferSize
import com.cdb96.ncmconverter4a.util.FileNameUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Tier1IoOptimizationTest {

    @Test
    fun passThroughBufferedOutputCoalescesSmallWritesAndForwardsLargeWritesDirectly() {
        val rawWrites = mutableListOf<Int>()
        val backing = ByteArrayOutputStream()
        val observedDirect = AtomicBoolean(false)
        val observedHalfDirect = AtomicBoolean(false)
        val largePayload = ByteArray(1024) { (it * 31 + 3).toByte() }
        val kggLikeChunk = ByteArray(48) { (it * 13 + 7).toByte() } // < scratch.size (64), >= directThreshold (32)

        val out = object : OutputStream() {
            override fun write(b: Int) {
                rawWrites.add(1)
                backing.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                rawWrites.add(len)
                if (b === largePayload) observedDirect.set(true)
                if (b === kggLikeChunk) observedHalfDirect.set(true)
                backing.write(b, off, len)
            }
        }

        val scratch = ByteArray(64)
        val buffered = PassThroughBufferedOutputStream(out, scratch)
        repeat(20) { i ->
            buffered.write(byteArrayOf(i.toByte(), (i + 1).toByte(), (i + 2).toByte()))
        }
        buffered.write(0x7F)
        // 61 bytes buffered so far -> 0 underlying writes yet.
        assertEquals(0, rawWrites.size)

        // Large write (>= scratch.size) flushes buffered 61 bytes, then forwards largePayload directly.
        buffered.write(largePayload, 0, largePayload.size)
        // KGG/NCM-style chunk (>= scratch.size / 2) with empty scratch forwards directly without copy.
        buffered.write(kggLikeChunk, 0, kggLikeChunk.size)
        // Small header + medium cover that still fits inside scratch coalesces into one write.
        val mediumCover = ByteArray(40) { (it * 5 + 1).toByte() }
        buffered.write(byteArrayOf(9, 8, 7))
        buffered.write(mediumCover, 0, mediumCover.size)
        // Next large chunk (> remaining scratch) flushes coalesced 43 bytes and writes directly without copy.
        buffered.write(kggLikeChunk, 0, kggLikeChunk.size)
        buffered.flush()

        assertEquals(listOf(61, 1024, 48, 43, 48), rawWrites)
        assertTrue(observedDirect.get(), "large write should bypass scratch buffer without copying")
        assertTrue(observedHalfDirect.get(), "half-scratch or larger audio chunk should bypass copy")

        val expected = ByteArrayOutputStream().apply {
            repeat(20) { i -> write(byteArrayOf(i.toByte(), (i + 1).toByte(), (i + 2).toByte())) }
            write(0x7F)
            write(largePayload)
            write(kggLikeChunk)
            write(byteArrayOf(9, 8, 7))
            write(mediumCover)
            write(kggLikeChunk)
        }.toByteArray()
        assertContentEquals(expected, backing.toByteArray())
    }

    @Test
    fun prefetchInputStreamPeekReadAndSkipMatchExactBytes() {
        val data = ByteArray(70_000) { (it * 17 + 5).toByte() }
        val directReadObserved = AtomicBoolean(false)
        val raw = object : InputStream() {
            var pos = 0
            override fun read(): Int = if (pos >= data.size) -1 else data[pos++].toInt() and 0xFF
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= data.size) return -1
                if (len >= 16_384) directReadObserved.set(true)
                val count = minOf(len, data.size - pos, 32_768)
                data.copyInto(b, off, pos, pos + count)
                pos += count
                return count
            }
            override fun skip(n: Long): Long = 0L // exercise fallback read-skip path too
        }

        val stream = PrefetchInputStream(raw, ByteArray(1024))
        val peeked = ByteArray(24)
        assertEquals(24, stream.peek(peeked))
        assertContentEquals(data.copyOfRange(0, 24), peeked)

        // Reading first 10 bytes must still start at 0.
        val first10 = ByteArray(10)
        assertEquals(10, stream.read(first10, 0, 10))
        assertContentEquals(data.copyOfRange(0, 10), first10)

        // Skip across buffered + unbuffered regions.
        stream.skipFully(4000L)
        val peekAfterSkip = ByteArray(8)
        assertEquals(8, stream.peek(peekAfterSkip))
        assertContentEquals(data.copyOfRange(4010, 4018), peekAfterSkip)

        // Consume remainder through BinaryInput with large chunk reads (>= prefetch buffer).
        val out = ByteArrayOutputStream()
        val largeChunk = ByteArray(16_384)
        val bin = BinaryInput(stream::read)
        while (true) {
            val n = bin.readAtMost(largeChunk)
            if (n == 0) break
            out.write(largeChunk, 0, n)
        }
        assertTrue(directReadObserved.get(), "large read after draining prefetch buffer should bypass prefetch copy")
        assertContentEquals(data.copyOfRange(4010, data.size), out.toByteArray())

        // Truncated skip must throw BinaryInputException.
        val shortStream = PrefetchInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)), ByteArray(64))
        assertFailsWith<BinaryInputException> {
            shortStream.skipFully(10L)
        }
    }

    private fun androidBufSize(inputCount: Int, threadCount: Int): Int =
        boundedWorkerBufferSize(inputCount, threadCount, 8 * 1024 * 1024, 512 * 1024, 2 * 1024 * 1024)

    @Test
    fun androidBufferSizeScalesAcrossThreadCounts() {
        assertEquals(2 * 1024 * 1024, androidBufSize(inputCount = 10, threadCount = 1))
        assertEquals(2 * 1024 * 1024, androidBufSize(inputCount = 10, threadCount = 4))
        assertEquals(1024 * 1024, androidBufSize(inputCount = 10, threadCount = 8))
        assertEquals(512 * 1024, androidBufSize(inputCount = 20, threadCount = 16))
        assertTrue(androidBufSize(inputCount = 10, threadCount = 5) % 256 == 0)
    }

    @Test
    fun serviceUsesBatchResolveFileNamesBeforeWorkers() = runBlocking {
        val samples = generateSampleCorpus(ncmMp3Count = 3, ncmFlacCount = 0, kgmCount = 1, payloadBytes = 64 * 1024)
        val inputMap = samples.associateBy { it.source }
        val perFileQueries = AtomicInteger(0)
        val batchResolveCalls = AtomicInteger(0)

        val files = object : ConversionFiles {
            override fun fileName(source: String): String {
                perFileQueries.incrementAndGet()
                return inputMap.getValue(source).fileName
            }

            override fun resolveFileNames(
                sources: List<String>,
                knownFileNames: Map<String, String>,
            ): Map<String, String> {
                batchResolveCalls.incrementAndGet()
                // Pre-resolve all except the last source; only the last should fall back to fileName().
                return sources.dropLast(1).associateWith { inputMap.getValue(it).fileName } + knownFileNames
            }

            override fun openInput(source: String): InputStream =
                ByteArrayInputStream(inputMap.getValue(source).encryptedBytes)

            override fun writeOutput(
                fileName: String,
                extension: String,
                mitigateConflicts: Boolean,
                write: (OutputStream) -> Unit,
            ): PendingOutput? {
                write(ByteArrayOutputStream())
                return null
            }

            override fun loadKggDatabase(database: String?, rootMode: Boolean) = error("unused")
        }

        val result = FileConversionService(files).processFiles(
            sources = samples.map { it.source },
            threadCount = 4,
            rawWriteMode = false,
            duplicateConflictMitigation = false,
        ) { _, _, _ -> }

        assertEquals(samples.size, result.successCount)
        assertEquals(1, batchResolveCalls.get())
        assertEquals(1, perFileQueries.get())
    }

    @Test
    fun smallFileCoalescesToSingleWriteWithBatchLifecycle() = runBlocking {
        val samples = generateSampleCorpus(ncmMp3Count = 1, ncmFlacCount = 1, kgmCount = 1, payloadBytes = 64 * 1024)
        val inputMap = samples.associateBy { it.source }
        val writeCalls = AtomicInteger(0)
        val beginCount = AtomicInteger(0)
        val endCount = AtomicInteger(0)
        val outputs = ConcurrentHashMap<String, ByteArray>()

        val files = object : ConversionFiles {
            override fun fileName(source: String) = inputMap.getValue(source).fileName
            override fun beginBatch(mitigateConflicts: Boolean) {
                beginCount.incrementAndGet()
            }
            override fun endBatch() {
                endCount.incrementAndGet()
            }
            override fun openInput(source: String): InputStream =
                ByteArrayInputStream(inputMap.getValue(source).encryptedBytes)
            override fun writeOutput(
                fileName: String,
                extension: String,
                mitigateConflicts: Boolean,
                write: (OutputStream) -> Unit,
            ): PendingOutput? {
                val outName = "$fileName.$extension"
                val baos = ByteArrayOutputStream()
                val countedOut = object : OutputStream() {
                    override fun write(b: Int) {
                        writeCalls.incrementAndGet()
                        baos.write(b)
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        writeCalls.incrementAndGet()
                        baos.write(b, off, len)
                    }
                }
                write(countedOut)
                outputs[outName] = baos.toByteArray()
                return null
            }
            override fun loadKggDatabase(database: String?, rootMode: Boolean) = error("unused")
            override fun bufferSize(inputCount: Int, threadCount: Int) = 256 * 1024
        }

        val result = FileConversionService(files).processFiles(
            sources = samples.map { it.source },
            threadCount = 1,
            rawWriteMode = false,
            duplicateConflictMitigation = false,
            knownFileNames = samples.associate { it.source to it.fileName },
        ) { _, _, _ -> }

        assertEquals(samples.size, result.successCount)
        assertEquals(1, beginCount.get())
        assertEquals(1, endCount.get())
        assertEquals(samples.size, writeCalls.get(), "each small file should need only 1 OutputStream.write()")

        val baseline = convertAllWithBaseline(samples, rawWriteMode = false)
        assertEquals(baseline.keys, outputs.keys)
        for ((name, expected) in baseline) {
            assertContentEquals(expected, outputs.getValue(name), "coalesced output mismatch: $name")
        }
    }

    @Test
    fun serviceUsesKnownNamesResolvesMissingConcurrentlyAndOverlapsPublish() = runBlocking {
        val samples = generateSampleCorpus(ncmMp3Count = 3, ncmFlacCount = 2, kgmCount = 2, payloadBytes = 320 * 1024)
        val inputMap = samples.associate { it.source to it }
        val fileNameQueries = ConcurrentHashMap.newKeySet<String>()
        val outputs = ConcurrentHashMap<String, ByteArray>()
        val published = ConcurrentHashMap<String, Boolean>()
        val discarded = ConcurrentHashMap<String, Boolean>()
        val activePublishes = AtomicInteger(0)
        val maxConcurrentPublishes = AtomicInteger(0)

        val files = object : ConversionFiles {
            override fun fileName(source: String): String {
                fileNameQueries.add(source)
                // Simulate Binder IPC latency only for sources not in knownFileNames.
                Thread.sleep(10)
                return inputMap.getValue(source).fileName
            }

            override fun openInput(source: String): InputStream =
                ByteArrayInputStream(inputMap.getValue(source).encryptedBytes)

            override fun writeOutput(
                fileName: String,
                extension: String,
                mitigateConflicts: Boolean,
                write: (OutputStream) -> Unit,
            ): PendingOutput {
                val outName = "$fileName.$extension"
                val buffer = ByteArrayOutputStream()
                write(buffer)
                outputs[outName] = buffer.toByteArray()
                return object : PendingOutput {
                    override fun publish() {
                        val cur = activePublishes.incrementAndGet()
                        maxConcurrentPublishes.accumulateAndGet(cur, ::maxOf)
                        Thread.sleep(25)
                        activePublishes.decrementAndGet()
                        published[outName] = true
                    }

                    override fun discard() {
                        discarded[outName] = true
                    }
                }
            }

            override fun loadKggDatabase(database: String?, rootMode: Boolean) =
                error("not used")

            override fun publishParallelism(): Int = 2
        }

        // Provide knownFileNames for first 4 files only; remaining 3 must be queried inside workers.
        val known = samples.take(4).associate { it.source to it.fileName }
        val service = FileConversionService(files)
        val progressUpdates = mutableListOf<Int>()
        val result = service.processFiles(
            sources = samples.map { it.source },
            threadCount = 4,
            rawWriteMode = false,
            duplicateConflictMitigation = false,
            knownFileNames = known,
        ) { processed, _, _ ->
            progressUpdates.add(processed)
        }

        assertEquals(samples.size, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals(samples.drop(4).map { it.source }.toSet(), fileNameQueries)
        assertEquals(samples.size, published.size)
        assertTrue(discarded.isEmpty())
        assertTrue(maxConcurrentPublishes.get() <= 2)
        assertEquals(samples.size, progressUpdates.maxOrNull())

        // Verify byte-for-byte identity against baseline single-file conversion.
        val baseline = convertAllWithBaseline(samples, rawWriteMode = false)
        assertEquals(baseline.keys, outputs.keys)
        for ((name, expectedBytes) in baseline) {
            assertContentEquals(expectedBytes, outputs.getValue(name), "output mismatch for $name")
        }
    }

    @Test
    fun failedOrCancelledPublishDiscardsPendingOutput() = runBlocking {
        val samples = generateSampleCorpus(ncmMp3Count = 3, ncmFlacCount = 0, kgmCount = 0, payloadBytes = 64 * 1024)
        val inputMap = samples.associate { it.source to it }
        val discarded = ConcurrentHashMap.newKeySet<String>()

        val failSecondFiles = object : ConversionFiles {
            override fun fileName(source: String): String = inputMap.getValue(source).fileName
            override fun openInput(source: String): InputStream =
                ByteArrayInputStream(inputMap.getValue(source).encryptedBytes)

            override fun writeOutput(
                fileName: String,
                extension: String,
                mitigateConflicts: Boolean,
                write: (OutputStream) -> Unit,
            ): PendingOutput {
                val outName = "$fileName.$extension"
                write(ByteArrayOutputStream())
                return object : PendingOutput {
                    override fun publish() {
                        if (outName.contains("Song_1")) {
                            throw IllegalStateException("simulated publish failure")
                        }
                    }

                    override fun discard() {
                        discarded.add(outName)
                    }
                }
            }

            override fun loadKggDatabase(database: String?, rootMode: Boolean) = error("not used")
        }

        val service = FileConversionService(failSecondFiles)
        val failResult = service.processFiles(
            sources = samples.map { it.source },
            threadCount = 2,
            rawWriteMode = false,
            duplicateConflictMitigation = false,
        ) { _, _, _ -> }

        assertEquals(2, failResult.successCount)
        assertEquals(1, failResult.failureCount)
        assertEquals(1, discarded.size)

        // Now verify cancellation cleans up pending outputs.
        val cancelDiscarded = ConcurrentHashMap.newKeySet<String>()
        val cancelCreated = AtomicInteger(0)
        val cancelFiles = object : ConversionFiles {
            override fun fileName(source: String): String = inputMap.getValue(source).fileName
            override fun openInput(source: String): InputStream =
                ByteArrayInputStream(inputMap.getValue(source).encryptedBytes)

            override fun writeOutput(
                fileName: String,
                extension: String,
                mitigateConflicts: Boolean,
                write: (OutputStream) -> Unit,
            ): PendingOutput {
                val id = cancelCreated.incrementAndGet()
                write(ByteArrayOutputStream())
                return object : PendingOutput {
                    override fun publish() {
                        Thread.sleep(200)
                    }

                    override fun discard() {
                        cancelDiscarded.add("pending-$id")
                    }
                }
            }

            override fun loadKggDatabase(database: String?, rootMode: Boolean) = error("not used")
            override fun publishParallelism(): Int = 1
        }

        supervisorScope {
            val job = launch(Dispatchers.Default) {
                FileConversionService(cancelFiles).processFiles(
                    sources = samples.map { it.source },
                    threadCount = 3,
                    rawWriteMode = false,
                    duplicateConflictMitigation = false,
                ) { _, _, _ -> }
            }
            while (cancelCreated.get() < 2) {
                delay(10)
            }
            job.cancelAndJoin()
        }
        assertTrue(cancelDiscarded.isNotEmpty(), "cancelled pending outputs must be discarded")
    }

    @Test
    fun benchmarkTier1VsBaseline() = runBlocking {
        // 24 NCM MP3 (~1 MB + 128 KB cover), 8 NCM FLAC (~2 MB + 256 KB cover), 8 KGM (~1.5 MB)
        val samples = generateSampleCorpus(
            ncmMp3Count = 24,
            ncmFlacCount = 8,
            kgmCount = 8,
            mp3PayloadBytes = 1024 * 1024,
            flacPayloadBytes = 2 * 1024 * 1024,
            kgmPayloadBytes = 1536 * 1024,
            mp3CoverBytes = 128 * 1024,
            flacCoverBytes = 256 * 1024,
        )
        val totalInputMB = samples.sumOf { it.encryptedBytes.size.toLong() } / (1024.0 * 1024.0)

        // Warmup JIT & native libraries
        convertAllWithBaseline(samples.take(3), rawWriteMode = false)

        // ------------------------------------------------------------------
        // Benchmark 1: Simulated Android SAF + FUSE + MediaStore stack
        // Latencies modeled from Android Scoped Storage / MediaProvider behavior:
        //   - fileName() SAF query IPC: 4.0 ms
        //   - FUSE write() syscall overhead: 0.12 ms per write() call
        //   - MediaStore IS_PENDING=0 update + MediaScanner: 25.0 ms (serialized by MediaProvider lock)
        // ------------------------------------------------------------------
        val baselineSim = runSimulatedAndroidBaseline(
            samples = samples,
            threadCount = 4,
            rawWriteMode = false,
            nameIpcNs = 4_000_000L,
            fuseWriteOverheadNs = 120_000L,
            publishNs = 25_000_000L,
        )

        val tier1Sim = runSimulatedAndroidTier1(
            samples = samples,
            threadCount = 4,
            rawWriteMode = false,
            knownNames = samples.associate { it.source to it.fileName },
            nameIpcNs = 4_000_000L,
            fuseWriteOverheadNs = 120_000L,
            publishNs = 25_000_000L,
        )

        // Verify exact output parity between baseline and Tier 1
        assertEquals(baselineSim.outputs.keys, tier1Sim.outputs.keys)
        for ((name, expected) in baselineSim.outputs) {
            assertContentEquals(expected, tier1Sim.outputs.getValue(name), "sim mismatch: $name")
        }
        assertEquals(0, tier1Sim.nameIpcCalls)
        assertTrue(tier1Sim.writeCalls < baselineSim.writeCalls / 2, "expected coalesced writes to drop sharply")

        // ------------------------------------------------------------------
        // Benchmark 2: Real Host Disk Multi-File Batch (rawWriteMode = false)
        // ------------------------------------------------------------------
        val tempRoot = Files.createTempDirectory("ncm_tier1_bench").toFile()
        try {
            val inputDir = File(tempRoot, "in").apply { mkdirs() }
            val baseOutDir = File(tempRoot, "out_base").apply { mkdirs() }
            val tier1OutDir = File(tempRoot, "out_tier1").apply { mkdirs() }

            val diskSources = samples.map { sample ->
                val f = File(inputDir, sample.fileName)
                f.writeBytes(sample.encryptedBytes)
                f.absolutePath to sample.fileName
            }

            val baseDiskStart = System.nanoTime()
            runBaselineDiskBatch(diskSources.map { it.first }, baseOutDir, threadCount = 4, rawWriteMode = false)
            val baseDiskMs = (System.nanoTime() - baseDiskStart) / 1_000_000.0

            val tier1DiskStart = System.nanoTime()
            val tier1DiskResult = FileConversionService(
                object : ConversionFiles {
                    override fun fileName(source: String) = File(source).name
                    override fun openInput(source: String) = File(source).inputStream()
                    override fun writeOutput(
                        fileName: String,
                        extension: String,
                        mitigateConflicts: Boolean,
                        write: (OutputStream) -> Unit,
                    ): PendingOutput? {
                        File(tier1OutDir, FileNameUtils.outputFileName(fileName, extension)).outputStream().use(write)
                        return null
                    }
                    override fun loadKggDatabase(database: String?, rootMode: Boolean) = error("unused")
                    override fun bufferSize(inputCount: Int, threadCount: Int) = androidBufSize(inputCount, threadCount)
                }
            ).processFiles(
                sources = diskSources.map { it.first },
                threadCount = 4,
                rawWriteMode = false,
                duplicateConflictMitigation = false,
                knownFileNames = diskSources.toMap(),
            ) { _, _, _ -> }
            val tier1DiskMs = (System.nanoTime() - tier1DiskStart) / 1_000_000.0

            assertEquals(samples.size, tier1DiskResult.successCount)
            val baseFiles = baseOutDir.listFiles()?.associateBy { it.name }.orEmpty()
            val tier1Files = tier1OutDir.listFiles()?.associateBy { it.name }.orEmpty()
            assertEquals(baseFiles.keys, tier1Files.keys)
            for ((name, f) in baseFiles) {
                assertContentEquals(f.readBytes(), tier1Files.getValue(name).readBytes(), "disk mismatch: $name")
            }

            val simSpeedup = baselineSim.elapsedMs.toDouble() / tier1Sim.elapsedMs.toDouble()
            val diskSpeedup = baseDiskMs / tier1DiskMs
            println(
                """
                |=== Tier-1 Android I/O Benchmark (${samples.size} files, ${"%.1f".format(totalInputMB)} MB, 4 threads) ===
                |[Simulated Android SAF+FUSE+MediaStore]
                |  Baseline : ${baselineSim.elapsedMs} ms (${"%.1f".format(totalInputMB / (baselineSim.elapsedMs / 1000.0))} MB/s) | write() calls=${baselineSim.writeCalls}, name IPC=${baselineSim.nameIpcCalls}
                |  Tier-1   : ${tier1Sim.elapsedMs} ms (${"%.1f".format(totalInputMB / (tier1Sim.elapsedMs / 1000.0))} MB/s) | write() calls=${tier1Sim.writeCalls}, name IPC=${tier1Sim.nameIpcCalls}
                |  Speedup  : ${"%.2f".format(simSpeedup)}x
                |[Real Host Disk Batch]
                |  Baseline : ${"%.1f".format(baseDiskMs)} ms (${"%.1f".format(totalInputMB / (baseDiskMs / 1000.0))} MB/s)
                |  Tier-1   : ${"%.1f".format(tier1DiskMs)} ms (${"%.1f".format(totalInputMB / (tier1DiskMs / 1000.0))} MB/s)
                |  Speedup  : ${"%.2f".format(diskSpeedup)}x
                """.trimMargin()
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    // ======================== Helpers & Baseline Modeling ========================

    private data class SampleFile(
        val source: String,
        val fileName: String,
        val encryptedBytes: ByteArray,
    )

    private data class SimRunResult(
        val elapsedMs: Long,
        val writeCalls: Int,
        val nameIpcCalls: Int,
        val outputs: Map<String, ByteArray>,
    )

    private fun busyPauseNanos(nanos: Long) {
        if (nanos <= 0L) return
        val deadline = System.nanoTime() + nanos
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
    }

    private suspend fun runSimulatedAndroidBaseline(
        samples: List<SampleFile>,
        threadCount: Int,
        rawWriteMode: Boolean,
        nameIpcNs: Long,
        fuseWriteOverheadNs: Long,
        publishNs: Long,
    ): SimRunResult {
        val sampleMap = samples.associateBy { it.source }
        val outputs = ConcurrentHashMap<String, ByteArray>()
        val writeCalls = AtomicInteger(0)
        val nameIpcCalls = AtomicInteger(0)
        val mediaProviderLock = Any()

        val start = System.nanoTime()
        // Baseline 1: serial fileName() resolution before launching workers
        val names = withContext(Dispatchers.IO) {
            samples.map { sample ->
                nameIpcCalls.incrementAndGet()
                busyPauseNanos(nameIpcNs)
                sample.fileName
            }
        }
        val dispatcher = Dispatchers.IO.limitedParallelism(threadCount)
        supervisorScope {
            samples.mapIndexed { idx, sample ->
                async(dispatcher) {
                    val fileName = names[idx]
                    BufferedInputStream(ByteArrayInputStream(sampleMap.getValue(sample.source).encryptedBytes)).use { input ->
                        input.mark(24)
                        val header = ByteArray(24)
                        val size = BinaryInput(input::read).readAtMost(header)
                        input.reset()
                        val bufSize = NCMConverter.AUDIO_BUFFER_SIZE // fixed 256 KB
                        when {
                            header.copyOf(size).size >= 8 && header.copyOfRange(0, 8).contentEquals("CTENFDAM".encodeToByteArray()) -> {
                                val binIn = BinaryInput(input::read)
                                val info = NCMConverter.readHeader(binIn, includeCover = !rawWriteMode)
                                val outName = FileNameUtils.outputFileName("${info.musicArtists} - ${info.musicName}", info.format)
                                val baos = ByteArrayOutputStream()
                                val fuseOut = object : OutputStream() {
                                    override fun write(b: Int) {
                                        writeCalls.incrementAndGet()
                                        busyPauseNanos(fuseWriteOverheadNs)
                                        baos.write(b)
                                    }
                                    override fun write(b: ByteArray, off: Int, len: Int) {
                                        writeCalls.incrementAndGet()
                                        busyPauseNanos(fuseWriteOverheadNs)
                                        baos.write(b, off, len)
                                    }
                                }
                                NCMConverter.writeAudio(binIn, BinaryOutput(fuseOut::write), info, rawWriteMode, bufSize)
                                outputs[outName] = baos.toByteArray()
                                synchronized(mediaProviderLock) { busyPauseNanos(publishNs) }
                            }
                            else -> {
                                val binIn = BinaryInput(input::read)
                                val hdr = ByteArray(KGMConverter.HEADER_LENGTH)
                                binIn.readFully(hdr)
                                val key = KGMConverter.getOwnKeyBytes(hdr)
                                val firstChunk = ByteArray(bufSize)
                                val firstSize = binIn.readChunk(firstChunk)
                                val format = KGMConverter.detectFormat(firstChunk[0], key)
                                val outName = FileNameUtils.outputFileName(FileNameUtils.removeLastExtension(fileName), format)
                                val baos = ByteArrayOutputStream()
                                val fuseOut = object : OutputStream() {
                                    override fun write(b: Int) {
                                        writeCalls.incrementAndGet()
                                        busyPauseNanos(fuseWriteOverheadNs)
                                        baos.write(b)
                                    }
                                    override fun write(b: ByteArray, off: Int, len: Int) {
                                        writeCalls.incrementAndGet()
                                        busyPauseNanos(fuseWriteOverheadNs)
                                        baos.write(b, off, len)
                                    }
                                }
                                KGMConverter.decrypt(key, firstChunk, firstSize, bufSize, binIn::readChunk) { b, c ->
                                    fuseOut.write(b, 0, c)
                                }
                                outputs[outName] = baos.toByteArray()
                                synchronized(mediaProviderLock) { busyPauseNanos(publishNs) }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        return SimRunResult(elapsedMs, writeCalls.get(), nameIpcCalls.get(), outputs)
    }

    private suspend fun runSimulatedAndroidTier1(
        samples: List<SampleFile>,
        threadCount: Int,
        rawWriteMode: Boolean,
        knownNames: Map<String, String>,
        nameIpcNs: Long,
        fuseWriteOverheadNs: Long,
        publishNs: Long,
    ): SimRunResult {
        val sampleMap = samples.associateBy { it.source }
        val outputs = ConcurrentHashMap<String, ByteArray>()
        val writeCalls = AtomicInteger(0)
        val nameIpcCalls = AtomicInteger(0)
        val mediaProviderLock = Any()

        val files = object : ConversionFiles {
            override fun fileName(source: String): String {
                nameIpcCalls.incrementAndGet()
                busyPauseNanos(nameIpcNs)
                return sampleMap.getValue(source).fileName
            }

            override fun openInput(source: String): InputStream =
                ByteArrayInputStream(sampleMap.getValue(source).encryptedBytes)

            override fun writeOutput(
                fileName: String,
                extension: String,
                mitigateConflicts: Boolean,
                write: (OutputStream) -> Unit,
            ): PendingOutput {
                val outName = FileNameUtils.outputFileName(fileName, extension)
                val baos = ByteArrayOutputStream()
                val fuseOut = object : OutputStream() {
                    override fun write(b: Int) {
                        writeCalls.incrementAndGet()
                        busyPauseNanos(fuseWriteOverheadNs)
                        baos.write(b)
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        writeCalls.incrementAndGet()
                        busyPauseNanos(fuseWriteOverheadNs)
                        baos.write(b, off, len)
                    }
                }
                write(fuseOut)
                outputs[outName] = baos.toByteArray()
                return object : PendingOutput {
                    override fun publish() {
                        // MediaProvider SQLite/scanner lock is contended in Android; async queue keeps conversion workers free.
                        synchronized(mediaProviderLock) { busyPauseNanos(publishNs) }
                    }
                    override fun discard() {
                        outputs.remove(outName)
                    }
                }
            }

            override fun loadKggDatabase(database: String?, rootMode: Boolean) = error("unused")
            override fun bufferSize(inputCount: Int, threadCount: Int): Int = androidBufSize(inputCount, threadCount)
            override fun publishParallelism(): Int = 2
        }

        val start = System.nanoTime()
        val result = FileConversionService(files).processFiles(
            sources = samples.map { it.source },
            threadCount = threadCount,
            rawWriteMode = rawWriteMode,
            duplicateConflictMitigation = false,
            knownFileNames = knownNames,
        ) { _, _, _ -> }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(samples.size, result.successCount)
        return SimRunResult(elapsedMs, writeCalls.get(), nameIpcCalls.get(), outputs)
    }

    private suspend fun runBaselineDiskBatch(
        sources: List<String>,
        outDir: File,
        threadCount: Int,
        rawWriteMode: Boolean,
    ) {
        val names = withContext(Dispatchers.IO) { sources.map { File(it).name } }
        val dispatcher = Dispatchers.IO.limitedParallelism(threadCount)
        supervisorScope {
            sources.mapIndexed { idx, source ->
                async(dispatcher) {
                    val fileName = names[idx]
                    BufferedInputStream(File(source).inputStream()).use { input ->
                        input.mark(24)
                        val header = ByteArray(24)
                        val size = BinaryInput(input::read).readAtMost(header)
                        input.reset()
                        val bufSize = NCMConverter.AUDIO_BUFFER_SIZE
                        if (size >= 8 && header.copyOfRange(0, 8).contentEquals("CTENFDAM".encodeToByteArray())) {
                            val binIn = BinaryInput(input::read)
                            val info = NCMConverter.readHeader(binIn, includeCover = !rawWriteMode)
                            val outFile = File(outDir, FileNameUtils.outputFileName("${info.musicArtists} - ${info.musicName}", info.format))
                            outFile.outputStream().use { out ->
                                NCMConverter.writeAudio(binIn, BinaryOutput(out::write), info, rawWriteMode, bufSize)
                            }
                        } else {
                            val binIn = BinaryInput(input::read)
                            val hdr = ByteArray(KGMConverter.HEADER_LENGTH)
                            binIn.readFully(hdr)
                            val key = KGMConverter.getOwnKeyBytes(hdr)
                            val firstChunk = ByteArray(bufSize)
                            val firstSize = binIn.readChunk(firstChunk)
                            val format = KGMConverter.detectFormat(firstChunk[0], key)
                            val outFile = File(outDir, FileNameUtils.outputFileName(FileNameUtils.removeLastExtension(fileName), format))
                            outFile.outputStream().use { out ->
                                KGMConverter.decrypt(key, firstChunk, firstSize, bufSize, binIn::readChunk) { b, c ->
                                    out.write(b, 0, c)
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun convertAllWithBaseline(
        samples: List<SampleFile>,
        rawWriteMode: Boolean,
    ): Map<String, ByteArray> = runBlocking {
        runSimulatedAndroidBaseline(samples, threadCount = 1, rawWriteMode = rawWriteMode, 0L, 0L, 0L).outputs
    }

    private fun generateSampleCorpus(
        ncmMp3Count: Int,
        ncmFlacCount: Int,
        kgmCount: Int,
        payloadBytes: Int = 512 * 1024,
        mp3PayloadBytes: Int = payloadBytes,
        flacPayloadBytes: Int = payloadBytes,
        kgmPayloadBytes: Int = payloadBytes,
        mp3CoverBytes: Int = 32 * 1024,
        flacCoverBytes: Int = 64 * 1024,
    ): List<SampleFile> {
        val list = mutableListOf<SampleFile>()
        var idx = 0
        repeat(ncmMp3Count) {
            val id = idx++
            val name = "Track_${id}_MP3.ncm"
            list += SampleFile("content://test/$id", name, buildNcmMp3(id, mp3PayloadBytes, mp3CoverBytes))
        }
        repeat(ncmFlacCount) {
            val id = idx++
            val name = "Track_${id}_FLAC.ncm"
            list += SampleFile("content://test/$id", name, buildNcmFlac(id, flacPayloadBytes, flacCoverBytes))
        }
        repeat(kgmCount) {
            val id = idx++
            val name = "Track_${id}_KGM.kgm"
            list += SampleFile("content://test/$id", name, buildKgmMp3(id, kgmPayloadBytes))
        }
        return list
    }

    private fun buildNcmMp3(id: Int, payloadSize: Int, coverSize: Int): ByteArray {
        val key = ByteArray(15) { (it + id + 1).toByte() }
        val oldId3 = byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, 0, 0, 1, 0) + ByteArray(128)
        val audio = ByteArray(payloadSize) { ((it * 29 + id) and 0xFF).toByte() }
        val payload = oldId3 + audio
        RC4Decrypt.init(key)
        RC4Decrypt.decrypt(payload, payload.size)
        return buildNcmContainer(
            key = key,
            metadataJson = """{"musicName":"Song_$id","album":"Album_$id","artist":[["Artist_$id",0]],"format":"mp3"}""",
            cover = ByteArray(coverSize) { ((it + id) and 0xFF).toByte() },
            encryptedPayload = payload,
        )
    }

    private fun buildNcmFlac(id: Int, payloadSize: Int, coverSize: Int): ByteArray {
        val key = ByteArray(15) { (it * 3 + id + 1).toByte() }
        val streamInfo = byteArrayOf(0x00, 0x00, 0x00, 34) + ByteArray(34)
        val vorbisHeader = byteArrayOf(0x84.toByte(), 0x00, 0x00, 8) + ByteArray(8)
        val audio = ByteArray(payloadSize) { ((it * 13 + id) and 0xFF).toByte() }
        val payload = "fLaC".encodeToByteArray() + streamInfo + vorbisHeader + audio
        RC4Decrypt.init(key)
        RC4Decrypt.decrypt(payload, payload.size)
        return buildNcmContainer(
            key = key,
            metadataJson = """{"musicName":"Song_$id","album":"Album_$id","artist":[["Artist_$id",0]],"format":"flac"}""",
            cover = ByteArray(coverSize) { ((it * 5 + id) and 0xFF).toByte() },
            encryptedPayload = payload,
        )
    }

    private fun buildNcmContainer(
        key: ByteArray,
        metadataJson: String,
        cover: ByteArray,
        encryptedPayload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("CTENFDAM".toByteArray(StandardCharsets.US_ASCII))
        out.write(byteArrayOf(0, 0))

        val keyPlain = ByteArray(17 + key.size)
        "neteasecloudmusic".toByteArray(StandardCharsets.US_ASCII).copyInto(keyPlain)
        key.copyInto(keyPlain, 17)
        val encryptedKey = aesEncrypt(
            byteArrayOf(
                0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
                0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57
            ),
            keyPlain,
        )
        writeIntLE(out, encryptedKey.size)
        for (b in encryptedKey) out.write((b.toInt() xor 0x64) and 0xFF)

        val encryptedMeta = aesEncrypt(
            byteArrayOf(
                0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
                0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28
            ),
            metadataJson.toByteArray(StandardCharsets.UTF_8),
        )
        val encodedMeta = java.util.Base64.getEncoder().encode(encryptedMeta)
        val rawMeta = ByteArray(22 + encodedMeta.size)
        encodedMeta.copyInto(rawMeta, 22)
        writeIntLE(out, rawMeta.size)
        for (b in rawMeta) out.write((b.toInt() xor 0x63) and 0xFF)

        out.write(ByteArray(5))
        writeIntLE(out, cover.size)
        writeIntLE(out, cover.size)
        out.write(cover)
        out.write(encryptedPayload)
        return out.toByteArray()
    }

    private fun buildKgmMp3(id: Int, payloadSize: Int): ByteArray {
        val header = ByteArray(KGMConverter.HEADER_LENGTH)
        // KGM magic
        byteArrayOf(
            0x7C, 0xD5.toByte(), 0x32, 0xEB.toByte(),
            0x86.toByte(), 0x02, 0x7F, 0x4B,
            0xA8.toByte(), 0xAF.toByte(), 0xA6.toByte(), 0x8E.toByte(),
            0x0F, 0xFF.toByte(), 0x99.toByte(), 0x14
        ).copyInto(header, 0)
        val ownKey = ByteArray(17) { ((it * 19 + id + 1) and 0xFF).toByte() }
        ownKey[16] = 0
        ownKey.copyInto(header, 28, 0, 17)

        val plain = ByteArray(payloadSize) { ((it * 11 + id) and 0xFF).toByte() }
        plain[0] = 'I'.code.toByte()
        plain[1] = 'D'.code.toByte()
        plain[2] = '3'.code.toByte()
        // KGM is symmetric XOR with keystream; encrypting via KGMDecrypt produces valid ciphertext
        // Wait: KGMDecrypt applies b ^ (b << 4) on med8, so compute inverse for byte 0 (detectFormat) and full payload:
        // Wait: KGMDecrypt is: med8 = fileKey ^ cipher ^ mask; plain = med8 ^ (med8 << 4).
        // That means cipher = fileKey ^ mask ^ invNibble(plain)!
        // For test simplicity, run KGMDecrypt on zeros to extract the exact output of decrypt(zeros),
        // wait: if cipher = 0, out0 = (fk ^ m) ^ ((fk ^ m) << 4). Wait, XOR with cipher BEFORE (<<4) is linear over GF(2)!
        // Because f(x) = x ^ (x << 4) is linear, f(a ^ b) = f(a) ^ f(b).
        // Let invF(y): low nibble = y & 0x0F, high nibble = ((y >> 4) ^ (y & 0x0F)) & 0x0F!
        // Thus f(invF(y)) == y, and cipher = zeros_decrypted_inv ^ invF(plain)!
        // Even simpler: compute invF(plain) into buf, then pass buf through KGMDecrypt?
        // Wait: decrypt(buf) = f(fk ^ m ^ buf) = f(fk ^ m) ^ f(buf). If buf = invF(plain), then decrypt(buf) = f(fk ^ m) ^ plain.
        // Instead, encrypt directly: let z = decrypt(zeros) = f(fk ^ m). Since f is linear and invF(f(x)) = x,
        // fk ^ m = invF(z)! So cipher = invF(z ^ plain)!
        // Let's verify: f(fk ^ m ^ invF(z ^ plain)) = f(invF(z) ^ invF(z ^ plain)) = z ^ (z ^ plain) = plain!
        val z = ByteArray(payloadSize)
        KGMDecrypt.init(ownKey)
        KGMDecrypt.decrypt(z, 0, z.size)
        val cipher = ByteArray(payloadSize)
        for (i in cipher.indices) {
            val y = (z[i].toInt() xor plain[i].toInt()) and 0xFF
            val lo = y and 0x0F
            val hi = ((y ushr 4) xor lo) and 0x0F
            cipher[i] = ((hi shl 4) or lo).toByte()
        }
        return header + cipher
    }

    private fun aesEncrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(plain)
    }

    private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}
