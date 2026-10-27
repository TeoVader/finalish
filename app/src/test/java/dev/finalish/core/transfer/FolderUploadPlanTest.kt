package dev.finalish.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderUploadPlanTest {

    private fun f(name: String, size: Long = 10) = LocalFile("content://x/$name", name, size)

    private val root = LocalFolder(
        name = "Shoot",
        files = listOf(f("notes.txt", 5)),
        folders = listOf(
            LocalFolder("Day 1", files = listOf(f("a.mp4", 100), f("b.mp4", 200)), folders = listOf(LocalFolder("Stills", files = listOf(f("c.jpg", 7))))),
            LocalFolder("Empty"),
        ),
    )

    @Test fun parentsComeBeforeTheirSubfolders() {
        assertEquals(
            listOf("Shoot" to -1, "Day 1" to 0, "Empty" to 0, "Stills" to 1),
            FolderUploadPlan.plan(root).map { it.name to it.parent },
        )
    }

    @Test fun eachFolderKeepsItsOwnFiles() {
        assertEquals(
            listOf(listOf("notes.txt"), listOf("a.mp4", "b.mp4"), emptyList(), listOf("c.jpg")),
            FolderUploadPlan.plan(root).map { p -> p.files.map { it.name } },
        )
    }

    @Test fun summaryCountsFilesBytesAndSubfolders() {
        assertEquals(FolderUploadSummary(files = 4, bytes = 312, folders = 3), FolderUploadPlan.summary(root))
    }

    private val remote = FolderTree(
        name = "Shoot", id = "R",
        files = listOf(RemoteFile("n", "notes.txt", 5)),
        folders = listOf(
            FolderTree(
                "Day 1", id = "R1",
                files = listOf(RemoteFile("ra", "a.mp4", 999)),
                stacks = listOf(RemoteStack("b.mp4", listOf(RemoteFile("rb2", "b.mp4", 200), RemoteFile("rb1", "b_v1.mp4", 150)), "S")),
            ),
        ),
    )

    @Test fun mergeReusesFoldersWithTheSameName() {
        val plan = FolderUploadPlan.plan(root, into = remote)
        assertEquals(listOf("R", "R1", null, null), plan.map { it.existingId })
    }

    @Test fun skipLeavesOutFilesWithSameNameAndSize() {
        val plan = FolderUploadPlan.plan(root, into = remote, existing = ExistingFiles.Skip)
        // notes.txt (5) and b.mp4 (200, a stack's version) are there; a.mp4 differs in size.
        assertEquals(listOf(emptyList(), listOf("a.mp4"), emptyList(), listOf("c.jpg")), plan.map { p -> p.files.map { it.name } })
        assertEquals(2, FolderUploadPlan.present(root, remote))
    }

    @Test fun newVersionStacksOntoTheFileOrStack() {
        val day1 = FolderUploadPlan.plan(root, into = remote, existing = ExistingFiles.NewVersion)[1].files
        assertEquals(listOf("ra" to "file", "S" to "version_stack"), day1.map { it.stackOntoId to it.stackOntoType })
    }

    @Test fun newFolderGetsAFreeName() {
        assertEquals("Shoot (3)", FolderUploadPlan.freeName("Shoot", setOf("Shoot", "Shoot (2)")))
        assertEquals("Shoot (3)", FolderUploadPlan.plan(root, rename = "Shoot (3)").first().name)
    }
}
