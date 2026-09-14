package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.util.FileNameUtils
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.StandardOpenOption

internal data class ReservedDesktopOutput(
    val file: File,
    val stream: OutputStream
)

/**
 * Reserves a destination with CREATE_NEW. The existence check and creation are
 * one filesystem operation, so concurrent conversions cannot open the same
 * output path accidentally.
 */
internal class DesktopOutputAllocator(private val directory: File) {
    fun openUniqueOutput(
        requestedName: String,
        extension: String,
        mitigateConflicts: Boolean
    ): ReservedDesktopOutput {
        Files.createDirectories(directory.toPath())
        val baseName = FileNameUtils.sanitizeFileName(requestedName)
        val cleanExtension = extension.removePrefix(".").lowercase()
        require(cleanExtension.matches(Regex("[a-z0-9]+"))) {
            "invalid output extension: $extension"
        }

        var sequence = 0
        while (true) {
            val suffix = if (sequence == 0) "" else " ($sequence)"
            val path = directory.toPath().resolve("$baseName$suffix.$cleanExtension")
            try {
                val stream = Files.newOutputStream(
                    path,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                )
                return ReservedDesktopOutput(path.toFile(), stream)
            } catch (error: FileAlreadyExistsException) {
                if (!mitigateConflicts) throw error
                sequence++
            }
        }
    }

    fun <T> withUniqueOutput(
        requestedName: String,
        extension: String,
        mitigateConflicts: Boolean,
        block: (OutputStream) -> T
    ): Pair<File, T> {
        val reserved = openUniqueOutput(requestedName, extension, mitigateConflicts)
        try {
            val result = reserved.stream.use(block)
            return reserved.file to result
        } catch (error: Throwable) {
            Files.deleteIfExists(reserved.file.toPath())
            throw error
        }
    }
}
