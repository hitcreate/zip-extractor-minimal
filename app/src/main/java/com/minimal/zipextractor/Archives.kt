package com.minimal.zipextractor

import com.github.junrar.Archive
import net.lingala.zip4j.ZipFile
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.brotli.BrotliCompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.z.ZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

enum class ArchiveKind {
    ZIP, SEVEN_Z, RAR, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, TAR_Z,
    GZ, BZ2, XZ, LZMA, Z, BROTLI, CPIO, AR, UNKNOWN
}

class ArchiveEntry(val name: String, val isDirectory: Boolean, val size: Long)

fun interface ExtractSource {
    fun writeTo(out: OutputStream)
}

object Archives {

    private val MAGIC_ZIP = intArrayOf(0x50, 0x4B)
    private val MAGIC_7Z = intArrayOf(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)
    private val MAGIC_RAR = intArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)
    private val MAGIC_GZ = intArrayOf(0x1F, 0x8B)
    private val MAGIC_Z = intArrayOf(0x1F, 0x9D)
    private val MAGIC_BZ2 = intArrayOf(0x42, 0x5A, 0x68)
    private val MAGIC_XZ = intArrayOf(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)
    private val MAGIC_LZMA = intArrayOf(0x5D, 0x00, 0x00)
    private val MAGIC_AR = intArrayOf(0x21, 0x3C, 0x61, 0x72, 0x63, 0x68, 0x3E, 0x0A)
    private val MAGIC_USTAR = intArrayOf(0x75, 0x73, 0x74, 0x61, 0x72)

    fun detect(file: File, name: String): ArchiveKind {
        val lower = name.lowercase()
        val magic = ByteArray(512)
        val n = file.inputStream().use { it.read(magic) }
        fun has(offset: Int, bytes: IntArray): Boolean {
            if (n < offset + bytes.size) return false
            return bytes.withIndex().all { (i, v) -> (magic[offset + i].toInt() and 0xFF) == v }
        }
        fun ascii(offset: Int, text: String): Boolean =
            has(offset, text.map { it.code }.toIntArray())

        return when {
            has(0, MAGIC_ZIP) -> ArchiveKind.ZIP
            has(0, MAGIC_7Z) -> ArchiveKind.SEVEN_Z
            has(0, MAGIC_RAR) -> ArchiveKind.RAR
            has(0, MAGIC_GZ) ->
                if (lower.endsWith(".tgz") || lower.endsWith(".tar.gz")) ArchiveKind.TAR_GZ else ArchiveKind.GZ
            has(0, MAGIC_Z) ->
                if (lower.endsWith(".tar.z") || lower.endsWith(".taz")) ArchiveKind.TAR_Z else ArchiveKind.Z
            has(0, MAGIC_BZ2) ->
                if (lower.endsWith(".tbz") || lower.endsWith(".tbz2") || lower.endsWith(".tar.bz2")) ArchiveKind.TAR_BZ2 else ArchiveKind.BZ2
            has(0, MAGIC_XZ) ->
                if (lower.endsWith(".txz") || lower.endsWith(".tar.xz")) ArchiveKind.TAR_XZ else ArchiveKind.XZ
            has(0, MAGIC_LZMA) -> ArchiveKind.LZMA
            ascii(0, "070701") || ascii(0, "070702") || ascii(0, "070707") -> ArchiveKind.CPIO
            has(0, MAGIC_AR) -> ArchiveKind.AR
            has(257, MAGIC_USTAR) -> ArchiveKind.TAR
            lower.endsWith(".zip") -> ArchiveKind.ZIP
            lower.endsWith(".7z") -> ArchiveKind.SEVEN_Z
            lower.endsWith(".rar") -> ArchiveKind.RAR
            lower.endsWith(".tar") -> ArchiveKind.TAR
            lower.endsWith(".br") -> ArchiveKind.BROTLI
            lower.endsWith(".lzma") -> ArchiveKind.LZMA
            lower.endsWith(".cpio") -> ArchiveKind.CPIO
            lower.endsWith(".deb") || lower.endsWith(".ar") -> ArchiveKind.AR
            else -> ArchiveKind.UNKNOWN
        }
    }

    fun isSupported(kind: ArchiveKind) = kind != ArchiveKind.UNKNOWN

    /**
     * Iterate every entry in the archive in order. For files, [visitor] receives a non-null
     * [ExtractSource]; it is valid only for the duration of the visitor call (streaming formats
     * read forward), so consume it inside the callback. [cancelled] is polled between entries.
     */
    fun read(
        file: File,
        kind: ArchiveKind,
        password: CharArray?,
        cancelled: () -> Boolean = { false },
        visitor: (ArchiveEntry, ExtractSource?) -> Unit
    ) {
        when (kind) {
            ArchiveKind.ZIP -> readZip(file, password, cancelled, visitor)
            ArchiveKind.SEVEN_Z -> readSevenZ(file, password, cancelled, visitor)
            ArchiveKind.RAR -> readRar(file, password, cancelled, visitor)
            ArchiveKind.TAR -> readTar(BufferedInputStream(file.inputStream()), cancelled, visitor)
            ArchiveKind.TAR_GZ -> readTar(GzipCompressorInputStream(BufferedInputStream(file.inputStream())), cancelled, visitor)
            ArchiveKind.TAR_BZ2 -> readTar(BZip2CompressorInputStream(BufferedInputStream(file.inputStream())), cancelled, visitor)
            ArchiveKind.TAR_XZ -> readTar(XZCompressorInputStream(BufferedInputStream(file.inputStream())), cancelled, visitor)
            ArchiveKind.TAR_Z -> readTar(ZCompressorInputStream(BufferedInputStream(file.inputStream())), cancelled, visitor)
            ArchiveKind.GZ -> readSingle(GzipCompressorInputStream(BufferedInputStream(file.inputStream())), file.name, cancelled, visitor)
            ArchiveKind.BZ2 -> readSingle(BZip2CompressorInputStream(BufferedInputStream(file.inputStream())), file.name, cancelled, visitor)
            ArchiveKind.XZ -> readSingle(XZCompressorInputStream(BufferedInputStream(file.inputStream())), file.name, cancelled, visitor)
            ArchiveKind.LZMA -> readSingle(LZMACompressorInputStream(BufferedInputStream(file.inputStream())), file.name, cancelled, visitor)
            ArchiveKind.Z -> readSingle(ZCompressorInputStream(BufferedInputStream(file.inputStream())), file.name, cancelled, visitor)
            ArchiveKind.BROTLI -> readSingle(BrotliCompressorInputStream(BufferedInputStream(file.inputStream())), file.name, cancelled, visitor)
            ArchiveKind.CPIO -> readCpio(BufferedInputStream(file.inputStream()), cancelled, visitor)
            ArchiveKind.AR -> readAr(BufferedInputStream(file.inputStream()), cancelled, visitor)
            ArchiveKind.UNKNOWN -> throw IllegalArgumentException(
                "Unrecognised archive. Multi-volume sets (.part1.rar, .7z.001), zstd (.zst/.tar.zst) " +
                    "and PPMd-compressed 7z are not supported."
            )
        }
    }

    // ---- engines -----------------------------------------------------------

    private fun openZip(file: File, password: CharArray?): ZipFile =
        if (password != null && password.isNotEmpty()) ZipFile(file, password) else ZipFile(file)

    private fun readZip(file: File, password: CharArray?, cancelled: () -> Boolean, visitor: (ArchiveEntry, ExtractSource?) -> Unit) {
        val zip = openZip(file, password)
        // Legacy (non-UTF-8) filename fallback: if the default decode produced replacement
        // characters, retry as CP437 (the original ZIP default) or Latin-1.
        val probe = openZip(file, password)
        val needsFallback = runCatching { probe.fileHeaders.any { it.fileName.contains('\uFFFD') } }.getOrDefault(false)
        if (needsFallback) {
            val cs = runCatching { Charset.forName("IBM437") }.getOrDefault(Charsets.ISO_8859_1)
            zip.setCharset(cs)
        }
        for (header in zip.fileHeaders) {
            if (cancelled()) return
            val name = header.fileName
            if (header.isDirectory) {
                visitor(ArchiveEntry(name, true, 0), null)
            } else {
                visitor(ArchiveEntry(name, false, header.uncompressedSize)) { out ->
                    zip.getInputStream(header).use { it.copyTo(out) }
                }
            }
        }
    }

    private fun readSevenZ(file: File, password: CharArray?, cancelled: () -> Boolean, visitor: (ArchiveEntry, ExtractSource?) -> Unit) {
        val builder = SevenZFile.builder().setFile(file)
        if (password != null && password.isNotEmpty()) builder.setPassword(password)
        builder.get().use { sz ->
            var e = sz.nextEntry
            while (e != null) {
                if (cancelled()) return
                val entry = e
                if (entry.isDirectory) {
                    visitor(ArchiveEntry(entry.name, true, 0), null)
                } else {
                    val size = entry.size
                    visitor(ArchiveEntry(entry.name, false, size)) { out ->
                        val buf = ByteArray(1 shl 15)
                        var left = size
                        while (left > 0) {
                            val r = sz.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (r < 0) break
                            out.write(buf, 0, r)
                            left -= r
                        }
                    }
                }
                e = sz.nextEntry
            }
        }
    }

    private fun readRar(file: File, password: CharArray?, cancelled: () -> Boolean, visitor: (ArchiveEntry, ExtractSource?) -> Unit) {
        val archive = if (password != null && password.isNotEmpty()) Archive(file, password.concatToString()) else Archive(file)
        archive.use { ar ->
            for (header in ar.fileHeaders) {
                if (cancelled()) return
                val name = header.fileName
                if (header.isDirectory) {
                    visitor(ArchiveEntry(name, true, 0), null)
                } else {
                    visitor(ArchiveEntry(name, false, header.fullUnpackSize)) { out -> ar.extractFile(header, out) }
                }
            }
        }
    }

    private fun readTar(input: InputStream, cancelled: () -> Boolean, visitor: (ArchiveEntry, ExtractSource?) -> Unit) {
        TarArchiveInputStream(input).use { tar ->
            var e = tar.nextEntry
            while (e != null) {
                if (cancelled()) return
                val entry = e
                if (entry.isDirectory) {
                    visitor(ArchiveEntry(entry.name, true, 0), null)
                } else {
                    visitor(ArchiveEntry(entry.name, false, entry.size)) { out -> tar.copyTo(out) }
                }
                e = tar.nextEntry
            }
        }
    }

    private fun readCpio(input: InputStream, cancelled: () -> Boolean, visitor: (ArchiveEntry, ExtractSource?) -> Unit) {
        CpioArchiveInputStream(input).use { cpio ->
            var e = cpio.nextCPIOEntry
            while (e != null) {
                if (cancelled()) return
                val entry = e
                if (entry.isDirectory) {
                    visitor(ArchiveEntry(entry.name, true, 0), null)
                } else {
                    visitor(ArchiveEntry(entry.name, false, entry.size)) { out -> cpio.copyTo(out) }
                }
                e = cpio.nextCPIOEntry
            }
        }
    }

    private fun readAr(input: InputStream, cancelled: () -> Boolean, visitor: (ArchiveEntry, ExtractSource?) -> Unit) {
        ArArchiveInputStream(input).use { ar ->
            var e = ar.nextArEntry
            while (e != null) {
                if (cancelled()) return
                val entry = e
                if (entry.isDirectory) {
                    visitor(ArchiveEntry(entry.name, true, 0), null)
                } else {
                    visitor(ArchiveEntry(entry.name, false, entry.size)) { out -> ar.copyTo(out) }
                }
                e = ar.nextArEntry
            }
        }
    }

    private fun readSingle(input: InputStream, containerName: String, cancelled: () -> Boolean, visitor: (ArchiveEntry, ExtractSource?) -> Unit) {
        input.use { stream ->
            if (cancelled()) return
            visitor(ArchiveEntry(stripCompressSuffix(containerName), false, -1L)) { out -> stream.copyTo(out) }
        }
    }

    private fun stripCompressSuffix(name: String): String {
        val lower = name.lowercase()
        for (suffix in listOf(".gzip", ".bzip2", ".bz2", ".lzma", ".xz", ".gzip", ".br", ".gz", ".z", ".lz4", ".zst")) {
            if (lower.endsWith(suffix)) return name.dropLast(suffix.length)
        }
        return "$name.out"
    }
}
