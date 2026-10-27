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
}
