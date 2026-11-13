package com.localreader.twinleaf.data

import org.junit.Assert.*
import org.junit.Test

class LibraryFolderTest {
    @Test fun trimsNames() { assertEquals("查理九世",validatedFolderName("  查理九世  ",emptyList())) }
    @Test fun rejectsEmptyAndLongNames() {
        for(name in listOf("  ","书".repeat(41),"系列\n第二行")) {
            assertThrows(IllegalArgumentException::class.java) { validatedFolderName(name,emptyList()) }
        }
    }
    @Test fun rejectsDuplicatesIgnoringCase() {
        assertThrows(IllegalArgumentException::class.java) { validatedFolderName(" series ",listOf(LibraryFolder("a","Series"))) }
    }
    @Test fun allowsRenamingOwnFolder() {
        assertEquals("Series",validatedFolderName("Series",listOf(LibraryFolder("a","Series")),"a"))
    }
    @Test fun movingBookPreservesItsReadingState() {
        val book=LibraryBook("a","file:///a.pdf","书","PDF","original.jpg",progress=.45f,pdfPage=25,customCoverPath="custom.jpg",category="小说")
        assertEquals(book,book.copy(folderId="series").copy(folderId=null))
    }
}
