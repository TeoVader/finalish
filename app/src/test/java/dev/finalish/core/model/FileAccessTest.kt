package dev.finalish.core.model

import dev.finalish.ui.browser.FileAction
import dev.finalish.ui.browser.fileActionsFor
import dev.finalish.ui.home.ProjectAction
import dev.finalish.ui.home.projectActionsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileAccessTest {
    private val file = Asset("f", name = "Cut.mp4", type = "file", mediaType = "video/mp4")

    @Test fun `roles map to what the permissions guide allows`() {
        assertEquals(FileAccess(comment = false, manage = false, share = false, download = false, projectSettings = false, invite = false), FileAccess.ofRole("viewer"))
        assertEquals(FileAccess(comment = true, manage = false, share = false, download = false, projectSettings = false, invite = false), FileAccess.ofRole("commenter"))
        assertEquals(FileAccess(comment = true, manage = true, share = false, download = false, projectSettings = false, invite = false), FileAccess.ofRole("edit_only"))
        assertEquals(FileAccess(projectSettings = false, invite = false), FileAccess.ofRole("editor"))
        listOf("full_access", "workspace_admin", "admin", "owner").forEach { assertEquals(FileAccess.Full, FileAccess.ofRole(it)) }
    }

    @Test fun `an unknown or unreadable role keeps every action`() {
        assertEquals(FileAccess.Full, FileAccess.ofRole(null))
        assertEquals(FileAccess.Full, FileAccess.ofRole("something_new"))
        assertEquals(FileAction.entries.filter { it != FileAction.Members }, fileActionsFor(file, FileAccess.Full))
        assertEquals(ProjectAction.entries, projectActionsFor(FileAccess.ofRole("something_new")))
    }

    @Test fun `editors keep every file action`() {
        assertEquals(fileActionsFor(file, FileAccess.Full), fileActionsFor(file, FileAccess.ofRole("editor")))
    }

    @Test fun `project menu follows the role`() {
        val readOnly = listOf(ProjectAction.Members, ProjectAction.Collections)
        assertEquals(readOnly, projectActionsFor(FileAccess.ofRole("viewer")))
        assertEquals(readOnly, projectActionsFor(FileAccess.ofRole("commenter")))
        assertEquals(
            listOf(ProjectAction.Members, ProjectAction.Collections, ProjectAction.RecentlyDeleted),
            projectActionsFor(FileAccess.ofRole("edit_only")),
        )
        assertEquals(
            listOf(ProjectAction.Members, ProjectAction.ShareLinks, ProjectAction.Collections, ProjectAction.RecentlyDeleted, ProjectAction.SendLink),
            projectActionsFor(FileAccess.ofRole("editor")),
        )
        listOf("full_access", "owner").forEach { assertEquals(ProjectAction.entries, projectActionsFor(FileAccess.ofRole(it))) }
    }

    @Test fun `while the role loads only read-only project rows show`() {
        assertEquals(listOf(ProjectAction.Members, ProjectAction.Collections), projectActionsFor(null))
    }

    @Test fun `file menus follow the role`() {
        assertTrue(fileActionsFor(file, FileAccess.ofRole("viewer")).isEmpty())
        assertTrue(fileActionsFor(file, FileAccess.ofRole("commenter")).isEmpty())
        assertEquals(
            listOf(FileAction.Rename, FileAction.Move, FileAction.Copy, FileAction.Delete),
            fileActionsFor(file, FileAccess.ofRole("edit_only")),
        )
    }

    @Test fun `only full access and admins manage members`() {
        listOf("viewer", "commenter", "edit_only", "editor").forEach { assertFalse(it, FileAccess.ofRole(it).invite) }
        listOf("full_access", "workspace_admin", "admin", "owner", null, "something_new").forEach { assertTrue("$it", FileAccess.ofRole(it).invite) }
    }

    @Test fun `uploads and metadata edits need edit only or more`() {
        assertFalse(FileAccess.ofRole("viewer").manage)
        assertFalse(FileAccess.ofRole("commenter").manage)
        listOf("edit_only", "editor", "full_access", "owner", null).forEach { assertTrue("$it", FileAccess.ofRole(it).manage) }
    }

    @Test fun `commenters comment, viewers don't`() {
        assertFalse(FileAccess.ofRole("viewer").comment)
        listOf("commenter", "edit_only", "editor", "full_access", null).forEach { assertTrue("$it", FileAccess.ofRole(it).comment) }
    }

    @Test fun `read-only metadata covers the head version too`() {
        val status = MetadataField(fieldType = "select", name = "Status", mutable = true)
        val stack = Asset("s", name = "Cut", type = "version_stack", metadata = listOf(status), headVersion = file.copy(metadata = listOf(status)))
        val ro = stack.readOnlyMetadata()
        assertTrue(ro.metadata.none { it.mutable })
        assertTrue(ro.media.metadata.none { it.mutable })
        assertEquals(stack.media.metadata.map { it.name }, ro.media.metadata.map { it.name })
    }
}
