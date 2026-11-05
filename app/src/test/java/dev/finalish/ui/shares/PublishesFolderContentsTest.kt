package dev.finalish.ui.shares

import dev.finalish.core.model.Asset
import dev.finalish.core.model.Share
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishesFolderContentsTest {
    private val file = Asset("f", type = "file")
    private val folder = Asset("d", type = "folder")

    @Test
    fun `a folder on a public link asks first, also among files`() {
        val public = Share("s", access = "public")
        assertTrue(publishesFolderContents(public, listOf(folder)))
        assertTrue(publishesFolderContents(public, listOf(file, folder)))
    }

    @Test
    fun `files only or an invite-only link don't ask`() {
        assertFalse(publishesFolderContents(Share("s", access = "public"), listOf(file)))
        assertFalse(publishesFolderContents(Share("s", access = "secure"), listOf(folder)))
    }
}
