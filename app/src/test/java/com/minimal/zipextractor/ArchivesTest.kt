package com.minimal.zipextractor

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchivesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sample = mapOf(
        "hello.txt" to "hello world",
        "dir/nested.txt" to "nested contents"
    )

    // ---- helpers -----------------------------------------------------------

    private fun extractAll(file: File, kind: ArchiveKind, pw: CharArray? = null): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Archives.read(file, kind, pw) { entry, source ->
            if (!entry.isDirectory && source != null) {
                val bos = ByteArrayOutputStream()
                source.writeTo(bos)
                out[entry.name] = bos.toString("UTF-8")
            }
        }
        return out
    }

    private fun writeZipPlain(file: File) {
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, body) in sample) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
    }

    private fun writeZipEncrypted(file: File, password: String) {
        val z = ZipFile(file, password.toCharArray())
        for ((name, body) in sample) {
            val p = ZipParameters().apply {
                fileNameInZip = name
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_128
            }
            z.addStream(ByteArrayInputStream(body.toByteArray()), p)
        }
    }

    private fun writeTar(compressor: ((java.io.OutputStream) -> java.io.OutputStream)?, file: File) {
        val raw = file.outputStream()
        val out = compressor?.invoke(raw) ?: raw
        TarArchiveOutputStream(out).use { tar ->
            for ((name, body) in sample) {
                val bytes = body.toByteArray()
                val e = TarArchiveEntry(name).apply { size = bytes.size.toLong() }
                tar.putArchiveEntry(e)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }

    private fun writeSingleGz(file: File, name: String, body: String) {
        GzipCompressorOutputStream(file.outputStream()).use { it.write(body.toByteArray()) }
    }

    private fun writeCpio(file: File) {
        CpioArchiveOutputStream(file.outputStream()).use { cpio ->
            for ((name, body) in sample) {
                val bytes = body.toByteArray()
                val e = CpioArchiveEntry(name).apply { size = bytes.size.toLong() }
                cpio.putArchiveEntry(e)
                cpio.write(bytes)
                cpio.closeArchiveEntry()
            }
        }
    }

    private fun writeAr(file: File) {
        ArArchiveOutputStream(file.outputStream()).use { ar ->
            for ((name, body) in sample) {
                val bytes = body.toByteArray()
                val e = ArArchiveEntry(name, bytes.size.toLong())
                ar.putArchiveEntry(e)
                ar.write(bytes)
                ar.closeArchiveEntry()
            }
        }
    }

    private fun writeSingleLzma(file: File, body: String) {
        LZMACompressorOutputStream(file.outputStream()).use { it.write(body.toByteArray()) }
    }



    private fun writeSevenZ(file: File) {
        val srcDir = tmp.newFolder()
        val files = sample.map { (name, body) ->
            val flat = name.replace('/', '_')
            val f = File(srcDir, flat).apply { writeText(body) }
            flat to f
        }
        SevenZOutputFile(file).use { sz ->
            for ((name, f) in files) {
                val e: SevenZArchiveEntry = sz.createArchiveEntry(f, name)
                sz.putArchiveEntry(e)
                sz.write(f.readBytes())
                sz.closeArchiveEntry()
            }
        }
    }

    // ---- tests -------------------------------------------------------------

    @Test fun zipPlain() {
        val f = tmp.newFile("a.zip"); writeZipPlain(f)
        assertEquals(ArchiveKind.ZIP, Archives.detect(f, "a.zip"))
        assertEquals(sample, extractAll(f, ArchiveKind.ZIP))
    }

    @Test fun zipEncryptedNeedsPassword() {
        val f = tmp.newFile("enc.zip"); writeZipEncrypted(f, "hunter2")
        assertEquals(ArchiveKind.ZIP, Archives.detect(f, "enc.zip"))
        assertEquals(sample, extractAll(f, ArchiveKind.ZIP, "hunter2".toCharArray()))
        var threw = false
        try { extractAll(f, ArchiveKind.ZIP) } catch (e: Exception) { threw = true }
        assertTrue("wrong/missing password must fail", threw)
    }

    @Test fun tarPlain() {
        val f = tmp.newFile("a.tar"); writeTar(null, f)
        assertEquals(ArchiveKind.TAR, Archives.detect(f, "a.tar"))
        assertEquals(sample, extractAll(f, ArchiveKind.TAR))
    }

    @Test fun tarGz() {
        val f = tmp.newFile("a.tar.gz"); writeTar({ GzipCompressorOutputStream(it) }, f)
        assertEquals(ArchiveKind.TAR_GZ, Archives.detect(f, "a.tar.gz"))
        assertEquals(sample, extractAll(f, ArchiveKind.TAR_GZ))
    }

    @Test fun tarBz2() {
        val f = tmp.newFile("a.tar.bz2"); writeTar({ BZip2CompressorOutputStream(it) }, f)
        assertEquals(ArchiveKind.TAR_BZ2, Archives.detect(f, "a.tar.bz2"))
        assertEquals(sample, extractAll(f, ArchiveKind.TAR_BZ2))
    }

    @Test fun tarXz() {
        val f = tmp.newFile("a.tar.xz"); writeTar({ XZCompressorOutputStream(it) }, f)
        assertEquals(ArchiveKind.TAR_XZ, Archives.detect(f, "a.tar.xz"))
        assertEquals(sample, extractAll(f, ArchiveKind.TAR_XZ))
    }

    @Test fun gzSingleFile() {
        val f = tmp.newFile("data.txt.gz"); writeSingleGz(f, "data.txt.gz", "just text")
        assertEquals(ArchiveKind.GZ, Archives.detect(f, "data.txt.gz"))
        assertEquals(mapOf("data.txt" to "just text"), extractAll(f, ArchiveKind.GZ))
    }

    @Test fun sevenZip() {
        val f = tmp.newFile("a.7z"); writeSevenZ(f)
        assertEquals(ArchiveKind.SEVEN_Z, Archives.detect(f, "a.7z"))
        val got = extractAll(f, ArchiveKind.SEVEN_Z)
        assertEquals(setOf("hello.txt", "dir_nested.txt"), got.keys)
        assertEquals("hello world", got["hello.txt"])
        assertEquals("nested contents", got["dir_nested.txt"])
    }

    @Test fun rarFixtures() {
        for (name in listOf("rar4.rar", "rar5.rar", "test.rar")) {
            val f = File(javaClass.classLoader!!.getResource("rar/$name")!!.toURI())
            assertEquals("detect $name", ArchiveKind.RAR, Archives.detect(f, name))
            val got = extractAll(f, ArchiveKind.RAR)
            assertTrue("$name should yield at least one file", got.isNotEmpty())
            assertTrue("$name extracted bytes", got.values.any { it.isNotEmpty() })
        }
    }

    @Test fun rarPassword() {
        for (name in listOf("rar4-password-junrar.rar", "rar5-password-junrar.rar")) {
            val f = File(javaClass.classLoader!!.getResource("rar/$name")!!.toURI())
            assertEquals(ArchiveKind.RAR, Archives.detect(f, name))
            val got = extractAll(f, ArchiveKind.RAR, "junrar".toCharArray())
            assertTrue("$name with password", got.values.any { it.isNotEmpty() })
        }
    }

    @Test fun cpioArchive() {
        val f = tmp.newFile("a.cpio"); writeCpio(f)
        assertEquals(ArchiveKind.CPIO, Archives.detect(f, "a.cpio"))
        assertEquals(sample, extractAll(f, ArchiveKind.CPIO))
    }

    @Test fun arArchive() {
        val f = tmp.newFile("a.ar"); writeAr(f)
        assertEquals(ArchiveKind.AR, Archives.detect(f, "a.ar"))
        assertEquals(sample, extractAll(f, ArchiveKind.AR))
    }

    @Test fun lzmaSingleFile() {
        val f = tmp.newFile("notes.txt.lzma"); writeSingleLzma(f, "lzma payload")
        assertEquals(ArchiveKind.LZMA, Archives.detect(f, "notes.txt.lzma"))
        assertEquals(mapOf("notes.txt" to "lzma payload"), extractAll(f, ArchiveKind.LZMA))
    }

    // .Z (LZW compress) is read-only: commons-compress has no writer and the box has no
    // `compress` binary, so we assert detection only. Decoding is wired to ZCompressorInputStream.
    @Test fun compressZDetection() {
        val f = tmp.newFile("notes.txt.Z").apply { writeBytes(byteArrayOf(0x1F, 0x9D.toByte(), 0x90.toByte(), 0x00)) }
        assertEquals(ArchiveKind.Z, Archives.detect(f, "notes.txt.Z"))
        val t = tmp.newFile("a.tar.Z").apply { writeBytes(byteArrayOf(0x1F, 0x9D.toByte(), 0x90.toByte(), 0x00)) }
        assertEquals(ArchiveKind.TAR_Z, Archives.detect(t, "a.tar.Z"))
    }

    @Test fun unknownIsRejected() {
        val f = tmp.newFile("blob.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) }
        assertEquals(ArchiveKind.UNKNOWN, Archives.detect(f, "blob.bin"))
        assertFalse(Archives.isSupported(ArchiveKind.UNKNOWN))
    }
}
