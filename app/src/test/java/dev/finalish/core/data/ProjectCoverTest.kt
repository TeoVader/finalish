package dev.finalish.core.data

import dev.finalish.core.model.Asset
import dev.finalish.core.model.FolderPreview
import dev.finalish.core.model.Project
import dev.finalish.testing.FakeFrameio.Companion.page
import dev.finalish.testing.ViewModelTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cached covers hold signed thumbnail links: an expired one is fetched again, a valid one is not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProjectCoverTest : ViewModelTest() {
    private val route = "GET accounts/acc/folders/root/children"
    private val project = Project(id = "p1", rootFolderId = "root", updatedAt = "2026-01-01T00:00:00Z")
    private val folder = Asset(id = "root", type = "folder", updatedAt = "2026-01-01T00:00:00Z")

    private val expired = "https://example.com/old.jpg?Expires=1000&Signature=x"
    private val valid = "https://example.com/new.jpg?Expires=4102444800&Signature=y"
    private val alsoValid = "https://example.com/newer.jpg?Expires=4102444800&Signature=z"

    private fun child(url: String) =
        """{"id":"f1","type":"file","name":"cut.mov","media_links":{"thumbnail":{"url":"$url"}}}"""

    private fun cover() = runBlocking { frameio.repository.projectCover("acc", project) }
    private fun preview() = runBlocking { frameio.repository.folderPreview("acc", folder) }

    @Test fun `an expired cached cover is fetched again and replaced`() {
        frameio.on(route, page(child(expired)))
        assertEquals(listOf(expired), cover().thumbnails)

        frameio.on(route, page(child(valid)))
        assertEquals(listOf(valid), cover().thumbnails)

        // The fresh links replaced the old entry: no further request.
        frameio.calls.clear()
        frameio.on(route, page(child(alsoValid)))
        assertEquals(listOf(valid), cover().thumbnails)
        assertEquals(emptyList<String>(), frameio.calls)
    }

    @Test fun `a still valid cached cover makes no request`() {
        frameio.on(route, page(child(valid)))
        cover()
        frameio.calls.clear()

        assertEquals(listOf(valid), cover().thumbnails)
        assertEquals(emptyList<String>(), frameio.calls)
    }

    @Test fun `offline, an expired cover is kept`() {
        frameio.on(route, page(child(expired)))
        cover()
        frameio.on(route, """{"errors":[]}""", code = 500)

        assertEquals(listOf(expired), cover().thumbnails)
    }

    @Test fun `an expired folder preview is fetched again`() {
        frameio.on(route, page(child(expired)))
        assertEquals(FolderPreview(listOf(expired), 1), preview())

        frameio.on(route, page(child(valid)))
        assertEquals(listOf(valid), preview()?.thumbnails)
        frameio.calls.clear()
        assertEquals(listOf(valid), preview()?.thumbnails)
        assertEquals(emptyList<String>(), frameio.calls)
    }

    @Test fun `offline, an expired folder preview is returned instead of nothing`() {
        frameio.on(route, page(child(expired)))
        preview()
        frameio.on(route, """{"errors":[]}""", code = 500)

        assertEquals(listOf(expired), preview()?.thumbnails)
    }
}
