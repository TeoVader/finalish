package dev.finalish.core.data

import dev.finalish.core.model.User
import dev.finalish.core.model.linkImageKey
import dev.finalish.testing.FakeFrameio.Companion.page
import dev.finalish.testing.ViewModelTest
import dev.finalish.testing.awaitValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guest comment authors are remembered with their avatar, a signed link: once it expires the
 * audit log is asked again, without ever losing the name or asking twice in a session. The disk
 * copy keeps only the avatar's image cache key, so an author from an earlier session is asked again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GuestAuthorsTest : ViewModelTest() {
    private val audit = "GET accounts/acc/audit_logs"

    private val expired = "https://example.com/ann.jpg?exp=1000&sig=x"
    private val valid = "https://example.com/ann.jpg?exp=4102444800&sig=y"

    /** What an earlier session left on disk for Ann: her avatar's key, without the link. */
    private val annFromDisk = User(name = "Ann", avatarKey = linkImageKey(expired))

    @Before fun comments() {
        frameio.on(
            "GET accounts/acc/files/f1/comments",
            page("""{"id":"c1","text":"hi","created_at":"2026-01-02T10:00:00Z"}"""),
        )
    }

    /** An author remembered by an earlier session. */
    private fun remember(user: User) = runBlocking {
        JsonCache(app.cacheDir.resolve("test-api-cache"))
            .put("authors:acc", MapSerializer(String.serializer(), User.serializer()), mapOf("c1" to user))
    }

    private fun logged(avatar: String) =
        page("""{"resource_id":"c1","event_type":"comment_created","user":{"name":"Ann","avatar_url":"$avatar"}}""")

    private fun author(): User? =
        awaitValue(frameio.repository.comments("acc", "f1")) { !it.loading }.data!!.single().owner

    private fun auditCalls() = frameio.calls.count { it == audit }

    @Test fun `an expired avatar is refreshed from the audit log`() {
        remember(User(name = "Ann", avatarUrl = expired))
        frameio.on(audit, logged(valid))

        assertEquals(User(name = "Ann", avatarUrl = valid), author())
        assertEquals(1, auditCalls())

        // The fresh avatar was stored: no further lookup.
        frameio.calls.clear()
        assertEquals(User(name = "Ann", avatarUrl = valid), author())
        assertEquals(0, auditCalls())
    }

    @Test fun `a failed lookup keeps the name`() {
        remember(User(name = "Ann", avatarUrl = expired))
        frameio.on(audit, """{"errors":[]}""", code = 500)

        assertEquals(annFromDisk, author())
        assertEquals(1, auditCalls())
    }

    @Test fun `a refused lookup keeps the name and isn't repeated`() {
        remember(User(name = "Ann", avatarUrl = expired))
        frameio.on(audit, """{"errors":[]}""", code = 422)

        assertEquals("Ann", author()?.name)
        frameio.calls.clear()
        assertEquals("Ann", author()?.name)
        assertEquals(0, auditCalls())
    }

    @Test fun `an avatar from an earlier session is looked up once, its link wasn't kept`() {
        remember(User(name = "Ann", avatarUrl = valid))
        frameio.on(audit, logged(valid))

        assertEquals(User(name = "Ann", avatarUrl = valid), author())
        assertEquals(1, auditCalls())

        // Valid now, in memory: no further lookup.
        frameio.calls.clear()
        assertEquals(User(name = "Ann", avatarUrl = valid), author())
        assertEquals(0, auditCalls())
    }

    @Test fun `an author without an avatar makes no audit call`() {
        remember(User(name = "Ann"))
        frameio.on(audit, logged(valid))

        assertEquals(User(name = "Ann"), author())
        assertEquals(0, auditCalls())
    }

    @Test fun `an author the log doesn't have keeps the name and isn't looked up again`() {
        remember(User(name = "Ann", avatarUrl = expired))
        frameio.on(audit, page())

        assertEquals(annFromDisk, author())
        assertEquals(1, auditCalls())

        frameio.calls.clear()
        assertEquals(annFromDisk, author())
        assertEquals(0, auditCalls())
    }

    @Test fun `an avatar the log returns already expired isn't looked up again`() {
        remember(User(name = "Ann", avatarUrl = expired))
        frameio.on(audit, logged(expired))

        assertEquals("Ann", author()?.name)
        frameio.calls.clear()
        assertEquals("Ann", author()?.name)
        assertEquals(0, auditCalls())
    }
}
