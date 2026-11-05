package dev.finalish.core.data

import dev.finalish.core.model.Asset
import dev.finalish.testing.FakeFrameio.Companion.data
import dev.finalish.testing.FakeFrameio.Companion.page
import dev.finalish.testing.ViewModelTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShareAssetsTest : ViewModelTest() {
    private val route = "GET accounts/acc/shares/s1/assets"
    private val bare = """{"id":"a1","type":"file","name":"cut.mov"}"""
    private val pictured = """{"id":"a1","type":"file","name":"cut.mov","media_links":{"thumbnail":{"url":"https://x/t.jpg"}}}"""

    @Test fun `the listing comes before the thumbnail re-read, and the result carries the thumbnails`() = runBlocking {
        frameio.on(route, page(bare))
        frameio.on("GET accounts/acc/files/a1", data(pictured))
        val partials = mutableListOf<List<Asset>>()

        val all = frameio.repository.shareAssets("acc", "s1", partial = { partials += it })

        assertEquals(1, partials.size)
        assertNull(partials.single().single().thumbnailUrl)
        assertEquals("https://x/t.jpg", all.single().thumbnailUrl)
    }

    @Test fun `a listing with its thumbnails reads no file and needs no partial`() = runBlocking {
        frameio.on(route, page(pictured))
        val partials = mutableListOf<List<Asset>>()

        val all = frameio.repository.shareAssets("acc", "s1", partial = { partials += it })

        assertEquals("https://x/t.jpg", all.single().thumbnailUrl)
        assertTrue(partials.isEmpty())
        assertEquals(listOf(route), frameio.calls)
    }
}
