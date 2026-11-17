package dev.finalish.core.data

import dev.finalish.core.model.Asset
import dev.finalish.testing.ViewModelTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Copy to" and "Duplicate" from the viewer: each kind of asset goes to its own copy endpoint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CopyAssetTest : ViewModelTest() {
    private fun copied(type: String): List<String> = runBlocking {
        frameio.on("POST accounts/acc/${dev.finalish.core.network.FrameioApi.kindPath(type)}/x1/copy", "{}")
        frameio.repository.copy("acc", Asset(id = "x1", type = type, name = "cut"), "dest")
        frameio.calls.filter { it.startsWith("POST") }
    }

    @Test fun `a version stack is copied as a stack, not as a file`() =
        assertEquals(listOf("POST accounts/acc/version_stacks/x1/copy"), copied("version_stack"))

    @Test fun `a file is copied through the files endpoint`() =
        assertEquals(listOf("POST accounts/acc/files/x1/copy"), copied("file"))

    @Test fun `a folder is copied through the folders endpoint`() =
        assertEquals(listOf("POST accounts/acc/folders/x1/copy"), copied("folder"))
}
