package dev.finalish.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadBatchTest {

    private fun up(name: String, total: Long, done: Long = 0, state: TransferState = TransferState.Queued, folder: String = "Selects") =
        UploadItem(id = name, accountId = "a", folderId = "f", folderName = folder, uri = "content://$name", name = name, mimeType = "video/mp4", totalBytes = total, doneBytes = done, state = state)

    @Test fun singleFileUsesItsName() {
        val s = UploadBatch.summarize(listOf(up("A001.mov", 100, 40, TransferState.Running)))!!
        assertEquals("A001.mov", s.title)
        assertEquals(40L, s.done)
        assertEquals("Selects", s.destination)
    }

    @Test fun batchAddsUpAndCountsFinished() {
        val s = UploadBatch.summarize(
            listOf(
                up("a.mov", 100, 100, TransferState.Done),
                up("b.mov", 200, 50, TransferState.Running),
                up("c.mov", 300),
                up("d.mov", 400, state = TransferState.Cancelled),
            ),
        )!!
        assertEquals("Uploading 3 files · 1 done", s.title)
        assertEquals(2, s.active)
        assertEquals(150L, s.done)
        assertEquals(600L, s.total)
    }

    @Test fun mixedFoldersHaveNoDestination() {
        val s = UploadBatch.summarize(listOf(up("a", 1, folder = "One"), up("b", 1, folder = "Two")))!!
        assertNull(s.destination)
        assertEquals("Uploading 2 files", s.title)
    }

    @Test fun allCancelledIsNoBatch() {
        assertNull(UploadBatch.summarize(listOf(up("a", 1, state = TransferState.Cancelled))))
    }
}
