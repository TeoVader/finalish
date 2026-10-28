package dev.finalish.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderDownloadPlanTest {

    private fun f(id: String, name: String, size: Long = 10) = RemoteFile(id, name, size)

    private val tree = FolderTree(
        name = "Shoot",
        files = listOf(f("a", "notes.txt")),
        stacks = listOf(RemoteStack("Edit.mov", listOf(f("v3", "Edit_v3.mov", 30), f("v2", "Edit_v2.mov", 20), f("v1", "Edit_v1.mov", 10)))),
        folders = listOf(
            FolderTree("Day 1", files = listOf(f("b", "clip.mp4"), f("c", "CLIP.mp4"))),
            FolderTree("Empty"),
        ),
    )

    @Test fun latestVersionKeepsTheTree() {
        assertEquals(
            listOf("Shoot/Day 1/clip.mp4" to "b", "Shoot/Day 1/CLIP (2).mp4" to "c", "Shoot/notes.txt" to "a", "Shoot/Edit_v3.mov" to "v3"),
            FolderDownloadPlan.plan(tree, allVersions = false).map { it.path to it.fileId },
        )
    }

    @Test fun allVersionsGoInAFolderPerStackOldestFirstNumbering() {
        val stack = FolderDownloadPlan.plan(tree, allVersions = true).filter { it.path.startsWith("Shoot/Edit/") }
        assertEquals(
            listOf("Shoot/Edit/v3 - Edit_v3.mov", "Shoot/Edit/v2 - Edit_v2.mov", "Shoot/Edit/v1 - Edit_v1.mov"),
            stack.map { it.path },
        )
    }

    @Test fun summaryFollowsTheChoice() {
        assertEquals(FolderDownloadSummary(files = 4, bytes = 60, folders = 2, stacks = 1), FolderDownloadPlan.summary(tree, false))
        assertEquals(FolderDownloadSummary(files = 6, bytes = 90, folders = 2, stacks = 1), FolderDownloadPlan.summary(tree, true))
    }

    @Test fun serverNamesStayInsideTheFolder() {
        val evil = FolderTree("../..", folders = listOf(FolderTree("a/../../b", files = listOf(f("x", "../../etc/passwd")))))
        val path = FolderDownloadPlan.plan(evil, false).single().path
        assertEquals(listOf("folder", "a_.._.._b", "_.._etc_passwd"), path.split('/'))
    }

    @Test fun aFolderAndAFileWithTheSameNameDontClash() {
        val t = FolderTree("Root", files = listOf(f("1", "Assets")), folders = listOf(FolderTree("assets", files = listOf(f("2", "x.png")))))
        assertEquals(listOf("Root/assets/x.png", "Root/Assets (2)"), FolderDownloadPlan.plan(t, false).map { it.path })
    }
}
