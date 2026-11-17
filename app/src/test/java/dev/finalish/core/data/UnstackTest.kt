package dev.finalish.core.data

import dev.finalish.testing.FakeFrameio.Companion.data
import dev.finalish.testing.FakeFrameio.Companion.page
import dev.finalish.testing.ViewModelTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Unstack": every version moves, in one bulk call, into the folder that holds the stack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UnstackTest : ViewModelTest() {
    @Test fun `every version moves into the stack's folder`() = runBlocking {
        frameio.on("GET accounts/acc/version_stacks/s1", data("""{"id":"s1","type":"version_stack","parent_id":"f1"}"""))
        frameio.on("GET accounts/acc/version_stacks/s1/children", page("""{"id":"v2","type":"file"}""", """{"id":"v1","type":"file"}"""))
        frameio.on("PATCH accounts/acc/files/move", "", code = 204)
        frameio.on("DELETE accounts/acc/version_stacks/s1", "", code = 204)

        assertEquals(2, frameio.repository.unstack("acc", "s1"))

        val moves = frameio.bodies.filter { it.first == "PATCH accounts/acc/files/move" }.map { it.second }
        assertEquals(1, moves.size)
        assertTrue(moves[0], """"file_ids":["v2","v1"]""" in moves[0] && """"parent_id":"f1"""" in moves[0])
        // The empty stack Frame.io leaves behind goes, after the files are out.
        assertEquals("DELETE accounts/acc/version_stacks/s1", frameio.calls.last())
    }

    @Test fun `the files stay out when the empty stack can't be deleted`() = runBlocking {
        frameio.on("GET accounts/acc/version_stacks/s1", data("""{"id":"s1","type":"version_stack","parent_id":"f1"}"""))
        frameio.on("GET accounts/acc/version_stacks/s1/children", page("""{"id":"v1","type":"file"}"""))
        frameio.on("PATCH accounts/acc/files/move", "", code = 204)
        frameio.on("DELETE accounts/acc/version_stacks/s1", """{"errors":[{"detail":"Not allowed"}]}""", code = 403)

        assertEquals(1, frameio.repository.unstack("acc", "s1"))
    }
}
