package dev.finalish.ui.members

import dev.finalish.R
import dev.finalish.testing.FakeFrameio.Companion.page
import dev.finalish.testing.ViewModelTest
import dev.finalish.testing.awaitValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › People in the account: roles are "admin" or "member", set on the account itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AccountPeopleTest : ViewModelTest() {
    private val owner = """{"role":"owner","user":{"id":"u0","name":"Owen"}}"""
    private val bo = """{"role":"member","user":{"id":"u2","name":"Bo"}}"""

    private fun bind(myRoles: String): MembersViewModel {
        frameio.on("GET accounts", page("""{"id":"acc","display_name":"Studio","roles":$myRoles}"""))
        frameio.on("GET accounts/acc/users", page(owner, bo))
        val vm = MembersViewModel(app, frameio.repository)
        vm.onEvent(MembersEvent.Bind("acc", ACCOUNT_SCOPE, "acc"))
        awaitValue(vm.state) { it.members != null }
        return vm
    }

    @Test fun `the account's people come from the account, with no groups`() {
        val vm = bind("""["admin"]""")
        assertEquals(listOf("owner", "member"), vm.state.value.members!!.map { it.role })
        assertTrue(frameio.calls.none { "groups" in it })
        assertEquals(true, awaitValue(vm.canManage) { it != null })
    }

    @Test fun `only owners and admins manage account roles`() {
        val vm = bind("""["member"]""")
        assertEquals(false, awaitValue(vm.canManage) { it != null })
    }

    @Test fun `making someone admin patches the account user`() {
        val vm = bind("""["owner"]""")
        frameio.on("PATCH accounts/acc/users/u2", "", code = 204)
        val messages = record(vm.messages)

        vm.onEvent(MembersEvent.SetRole(vm.state.value.members!![1], "admin"))

        assertTrue(frameio.bodies.any { it.first == "PATCH accounts/acc/users/u2" && "\"role\":\"admin\"" in it.second })
        val expected = app.getString(R.string.members_is_now_role, "Bo", app.getString(R.string.members_role_account_admin))
        assertEquals(listOf(expected), awaitValue(messages) { it.isNotEmpty() })
    }
}
