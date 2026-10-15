package dev.finalish.ui.members

import dev.finalish.R
import dev.finalish.testing.FakeFrameio.Companion.page
import dev.finalish.testing.ViewModelTest
import dev.finalish.testing.awaitValue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MembersViewModelTest : ViewModelTest() {
    private val ana = """{"role":"editor","user":{"id":"u1","name":"Ana"}}"""
    private val bo = """{"role":"viewer","user":{"id":"u2","name":"Bo"}}"""
    private val cy = """{"role":"viewer","user":{"id":"u3","name":"Cy"}}"""
    private lateinit var vm: MembersViewModel

    @Before fun setUp() {
        frameio.on("GET accounts/acc/projects/p1/users", page(ana, bo))
        frameio.on("GET accounts/acc/projects/p1/groups", page("""{"group":{"id":"g1","name":"Sound"},"role":"commenter"}"""))
        vm = MembersViewModel(app, frameio.repository)
        vm.onEvent(MembersEvent.Bind("acc", "projects", "p1"))
        awaitValue(vm.state) { it.members != null && it.groups.isNotEmpty() }
    }

    @Test fun `loads people and groups`() {
        val state = vm.state.value
        assertEquals(listOf("Ana", "Bo"), state.members!!.map { it.user.displayName })
        assertEquals(listOf("Sound"), state.groups.map { it.group.name })
    }

    @Test fun `a role change shows at once and is confirmed`() {
        frameio.on("PATCH accounts/acc/projects/p1/users/u2", "", code = 204)
        val messages = record(vm.messages)
        vm.onEvent(MembersEvent.SetRole(vm.state.value.members!![1], "editor"))
        assertEquals("editor", vm.state.value.members!![1].role)
        val expected = app.getString(R.string.members_is_now_role, "Bo", app.getString(R.string.members_role_editor))
        assertEquals(listOf(expected), awaitValue(messages) { it.isNotEmpty() })
    }

    @Test fun `a removal the server refuses puts the person back in place`() {
        frameio.on("DELETE accounts/acc/projects/p1/users/u1", """{"errors":[{"detail":"Not allowed"}]}""", code = 403)
        val messages = record(vm.messages)
        vm.onEvent(MembersEvent.Remove(vm.state.value.members!![0]))
        assertEquals(listOf("Not allowed"), awaitValue(messages) { it.isNotEmpty() })
        assertEquals(listOf("u1", "u2"), awaitValue(vm.state) { it.members!!.size == 2 }.members!!.map { it.user.id })
    }

    @Test fun `candidates leave out people already here`() {
        frameio.on("GET accounts/acc/users", page(ana, bo, cy))
        vm.onEvent(MembersEvent.LoadCandidates)
        val state = awaitValue(vm.state) { it.candidates != null }
        assertEquals(listOf("u3"), state.candidates!!.map { it.user.id })
    }
}
