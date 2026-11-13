package com.localreader.twinleaf.epub

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun zip(vararg names: String): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out -> names.forEach {
            out.putNextEntry(ZipEntry(it)); out.write("chapter".toByteArray()); out.closeEntry()
        } }
        return ByteArrayInputStream(bytes.toByteArray())
    }
    @Test fun multipleFilesInExistingDirectoryExtractSuccessfully() {
        val root = temp.newFolder()
        EpubParser.unzipSafely(zip("mimetype", "content.opf", "OPS/a.xhtml", "OPS/b.xhtml"), root)
        assertEquals("chapter", root.resolve("OPS/b.xhtml").readText())
        assertTrue(root.resolve("content.opf").isFile)
    }
    @Test fun zipCannotEscapeExtractionRoot() {
        val root = temp.newFolder()
        assertThrows(IllegalStateException::class.java) { EpubParser.unzipSafely(zip("../escape.txt"), root) }
        assertFalse(root.parentFile.resolve("escape.txt").exists())
    }
}
