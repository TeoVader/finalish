package dev.finalish.ui.browser

import dev.finalish.R
import dev.finalish.testing.FakeFrameio.Companion.data
import dev.finalish.testing.FakeFrameio.Companion.page
import dev.finalish.testing.ViewModelTest
import dev.finalish.testing.awaitValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrashViewModelTest : ViewModelTest() {
    private val cut = """{"id":"a1","type":"file","name":"cut.mov","parent_id":"f1","project_id":"p1"}"""
    private val take = """{"id":"a2","type":"file","name":"take.mov","parent_id":"f1","project_id":"p1"}"""
    private lateinit var vm: TrashViewModel

    @Before fun setUp() {
        frameio.on("GET accounts/acc/projects/p1", data("""{"id":"p1","name":"Film","view_url":"https://next.frame.io/p1"}"""))
        frameio.on("GET accounts/acc/projects/p1/recently_deleted", page(cut, take))
        vm = TrashViewModel(app, frameio.repository)
    }

    @Test fun `binding loads the deleted items and the web link`() {
        vm.onEvent(TrashEvent.Bind("acc", "p1"))
        val state = awaitValue(vm.state) { it.items != null }
        assertEquals(listOf("a1", "a2"), state.items!!.map { it.id })
        assertEquals("https://next.frame.io/p1", state.webUrl)
        assertFalse(state.restoreUnavailable)
    }

    @Test fun `binding again to the same project does not reload`() {
        vm.onEvent(TrashEvent.Bind("acc", "p1"))
        awaitValue(vm.state) { it.items != null }
        vm.onEvent(TrashEvent.Bind("acc", "p1"))
        assertEquals(1, frameio.calls.count { it.endsWith("recently_deleted") })
    }

    @Test fun `restoring removes the item and says so`() {
        frameio.on("PATCH accounts/acc/files/a1/restore", "", code = 204)
        vm.onEvent(TrashEvent.Bind("acc", "p1"))
        awaitValue(vm.state) { it.items != null }
        val messages = record(vm.messages)
        vm.onEvent(TrashEvent.Restore(vm.state.value.items!!.first()))
        assertEquals(listOf(app.getString(R.string.browser_trash_restored, "cut.mov")), awaitValue(messages) { it.isNotEmpty() })
        assertEquals(listOf("a2"), vm.state.value.items!!.map { it.id })
    }

    @Test fun `restore missing on the account puts the item back and offers the web`() {
        // No route: the fake answers 404, as accounts without the experimental endpoint do.
        vm.onEvent(TrashEvent.Bind("acc", "p1"))
        awaitValue(vm.state) { it.items != null }
        val messages = record(vm.messages)
        vm.onEvent(TrashEvent.Restore(vm.state.value.items!!.first()))
        assertEquals(listOf(app.getString(R.string.browser_trash_restore_unavailable)), awaitValue(messages) { it.isNotEmpty() })
        val state = awaitValue(vm.state) { it.restoreUnavailable }
        assertEquals(listOf("a1", "a2"), state.items!!.map { it.id })
        assertTrue(state.restoreUnavailable)
    }

    @Test fun `a failed load shows the error`() {
        frameio.on("GET accounts/acc/projects/p1/recently_deleted", """{"errors":[{"detail":"Boom"}]}""", code = 500)
        vm.onEvent(TrashEvent.Bind("acc", "p1"))
        val state = awaitValue(vm.state) { it.error != null }
        assertEquals("Boom", state.error)
        assertEquals(null, state.items)
    }
}
