package dev.finalish.core.model

import dev.finalish.ui.browser.FileAction
import dev.finalish.ui.browser.fileActionsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileAccessTest {
    private val file = Asset("f", name = "Cut.mp4", type = "file", mediaType = "video/mp4")

    @Test fun `roles map to what the permissions guide allows`() {
        assertEquals(FileAccess(comment = false, manage = false, share = false, download = false), FileAccess.ofRole("viewer"))
        assertEquals(FileAccess(comment = true, manage = false, share = false, download = false), FileAccess.ofRole("commenter"))
        assertEquals(FileAccess(comment = true, manage = true, share = false, download = false), FileAccess.ofRole("edit_only"))
        listOf("editor", "full_access", "workspace_admin", "admin", "owner").forEach { assertEquals(FileAccess.Full, FileAccess.ofRole(it)) }
    }

    @Test fun `an unknown or unreadable role keeps every action`() {
        assertEquals(FileAccess.Full, FileAccess.ofRole(null))
        assertEquals(FileAccess.Full, FileAccess.ofRole("something_new"))
        assertEquals(FileAction.entries.filter { it != FileAction.Members }, fileActionsFor(file, FileAccess.Full))
    }

    @Test fun `file menus follow the role`() {
        assertTrue(fileActionsFor(file, FileAccess.ofRole("viewer")).isEmpty())
        assertTrue(fileActionsFor(file, FileAccess.ofRole("commenter")).isEmpty())
        assertEquals(
            listOf(FileAction.Rename, FileAction.Move, FileAction.Copy, FileAction.Delete),
            fileActionsFor(file, FileAccess.ofRole("edit_only")),
        )
    }
}
