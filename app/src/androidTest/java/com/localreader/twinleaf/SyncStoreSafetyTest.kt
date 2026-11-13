package com.localreader.twinleaf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.twinleaf.ink.InkPoint
import com.localreader.twinleaf.ink.InkStore
import com.localreader.twinleaf.ink.InkStroke
import com.localreader.twinleaf.note.NoteCodec
import com.localreader.twinleaf.note.NoteSnapshot
import com.localreader.twinleaf.note.NoteStore
import com.localreader.twinleaf.note.NoteViewport
import com.localreader.twinleaf.sync.CloudHead
import com.localreader.twinleaf.sync.CloudSyncProtocol
import com.localreader.twinleaf.sync.cloudHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncStoreSafetyTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun stroke(width:Float)=InkStroke(listOf(InkPoint(.2f,.3f)),0xFF234567.toInt(),width)

    @Test fun protocolRoundTripsShardsAndRejectsCorruptedContent()=runBlocking {
        val text=NoteCodec.encode(NoteSnapshot(strokes=List(33) { stroke(3f) }))
        val payload=CloudSyncProtocol.payload("note",text)
        val head=CloudHead(payload.hash,payload.index,123456000L)
        assertEquals(payload.text,CloudSyncProtocol.unpack("note",head) { payload.blobs.getValue(it) })
        val corrupted=runCatching {
            CloudSyncProtocol.unpack("note",head) { name -> payload.blobs.getValue(name)+" " }
        }.exceptionOrNull()
        assertTrue(corrupted is IllegalArgumentException)
        val cancelled=runCatching {
            CloudSyncProtocol.unpack("note",head) { throw CancellationException("cancelled") }
        }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
    }

    @Test fun protocolRejectsShardPathsBeforeReadingThem()=runBlocking {
        val index="""{"version":1,"channel":"ink","header":{"version":3},"pages":{"0":"../outside.json"}}"""
        val name="data-${cloudHash(index)}.json"
        val head=CloudHead("a".repeat(64),name,0)
        val requested=mutableListOf<String>()
        val error=runCatching {
            CloudSyncProtocol.unpack("ink",head) { requested+=it;index }
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(listOf(name),requested)
    }

    @Test fun pdfImportRejectsLocalEditAndActiveStroke()=runBlocking {
        val store=withContext(Dispatchers.Main) { InkStore.get(context,"sync-test:${UUID.randomUUID()}") }
        val initial=store.syncSnapshot()
        val remote=InkStore.encode(mapOf(1 to listOf(stroke(.004f))))
        withContext(Dispatchers.Main) { store.drawing=true }
        assertFalse(store.importForSyncIfUnchanged(remote,123456000L,hash(initial.second)))
        withContext(Dispatchers.Main) { store.drawing=false;store.change(0,listOf(stroke(.003f))) }
        assertFalse(store.importForSyncIfUnchanged(remote,123456000L,hash(initial.second)))
        assertEquals(setOf(0),InkStore.decode(store.syncSnapshot().second).keys)
    }

    @Test fun storeCompareAndSwapRequiresExactSnapshotHash()=runBlocking {
        val store=withContext(Dispatchers.Main) { InkStore.get(context,"sync-test:${UUID.randomUUID()}") }
        val snapshot=store.syncSnapshot().second
        val remote=InkStore.encode(mapOf(0 to listOf(stroke(.004f))))
        // Even semantically identical JSON with a trailing space is a different CAS token.
        assertFalse(store.importForSyncIfUnchanged(remote,123456000L,hash(snapshot+" ")))
        assertTrue(store.importForSyncIfUnchanged(remote,123456000L,hash(snapshot)))
        assertEquals(remote,store.syncSnapshot().second)
    }

    @Test fun noteImportRejectsUnsavedViewportAndPreservesValidatedImport()=runBlocking {
        val id="sync-test-${UUID.randomUUID()}"
        val store=withContext(Dispatchers.Main) { NoteStore.get(context,id) }
        val initial=store.syncSnapshot()
        val remote=NoteCodec.encode(NoteSnapshot(strokes=listOf(stroke(3f))))
        withContext(Dispatchers.Main) { store.updateViewport(NoteViewport(20f,30f,2f)) }
        assertFalse(store.importForSyncIfUnchanged(remote,123456000L,hash(initial.second)))
        val current=store.syncSnapshot()
        withContext(Dispatchers.Main) { store.persist();store.persist() }
        assertTrue(store.importForSyncIfUnchanged(remote,123456000L,hash(current.second)))
        val imported=store.syncSnapshot()
        assertEquals(remote,imported.second)
        assertEquals(123456000L,imported.first)
        assertEquals(NoteCodec.decode(remote),NoteCodec.decode(File(context.filesDir,"notes/$id.json").readText()))
    }

    @Test fun malformedRemoteNeverReplacesExistingPdfInk()=runBlocking {
        val store=withContext(Dispatchers.Main) { InkStore.get(context,"sync-test:${UUID.randomUUID()}") }
        store.syncSnapshot()
        withContext(Dispatchers.Main) { store.change(0,listOf(stroke(.003f))) }
        val before=store.syncSnapshot()
        val error=runCatching { store.importForSyncIfUnchanged("{broken",123456000L,hash(before.second)) }.exceptionOrNull()
        assertNotNull(error)
        assertEquals(before,store.syncSnapshot())
    }
}
