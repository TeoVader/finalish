package dev.finalish.core.notify

import dev.finalish.core.model.Comment
import dev.finalish.core.model.CommentMention
import dev.finalish.core.model.User
import dev.finalish.ui.notifications.headline
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityEventsTest {
    private val me = User(id = "me", name = "Noa")
    private val jordan = User(id = "p", name = "Tomás")
    private val priya = User(id = "s", name = "Ines")
    private val w = WatchedFile("f1", "a", "p1", "Cut.mp4")

    @Test fun splitsMentionsRepliesAndComments() {
        val reply = Comment(id = "r1", text = "agree", owner = jordan, createdAt = "2026-10-01T10:00:00Z")
        val mineTop = Comment(id = "c0", text = "first", owner = me, createdAt = "2026-09-30T10:00:00Z", replies = listOf(reply))
        val mention = Comment(id = "c1", text = "@Noa look", owner = priya, createdAt = "2026-10-01T11:00:00Z", mentions = listOf(CommentMention(0, 5, "@Noa", "me")))
        val other = Comment(id = "c2", text = "nice", owner = jordan, createdAt = "2026-10-01T12:00:00Z")
        val events = ActivityCheck.eventsFor(w, listOf(mineTop, mention, other), listOf(reply, mention, other), "me")
        assertEquals(listOf(ActivityEvent.Kind.Mention, ActivityEvent.Kind.Replies, ActivityEvent.Kind.Comments), events.map { it.kind })
        assertEquals("@Noa look", events[0].text)
        assertEquals(null, events[2].text)
    }

    @Test fun viewLinkNeedsProject() {
        assertEquals("https://next.frame.io/project/p1/view/f1", Notifier.viewLink("p1", "f1"))
        // `project//view/f1` would parse as a project called "view" and open the browser.
        assertEquals(null, Notifier.viewLink("", "f1"))
    }

    @Test fun headlines() {
        fun e(kind: ActivityEvent.Kind, actors: List<String>, count: Int) = ActivityEvent("x", kind, "a", "p", "Cut.mp4", actors = actors, count = count, at = "2026-10-01T10:00:00Z")
        assertEquals("Tomás left 2 comments on Cut.mp4", headline(e(ActivityEvent.Kind.Comments, listOf("Tomás"), 2)))
        assertEquals("Tomás and 1 other left a reply to you on Cut.mp4", headline(e(ActivityEvent.Kind.Replies, listOf("Ines", "Tomás"), 1)))
        assertEquals("Ines mentioned you on Cut.mp4", headline(e(ActivityEvent.Kind.Mention, listOf("Ines"), 1)))
        assertEquals("Your upload Cut.mp4 is ready to review", headline(e(ActivityEvent.Kind.UploadReady, emptyList(), 1)))
        assertEquals("Your link Cut.mp4 was opened 3 times", headline(e(ActivityEvent.Kind.ShareViewed, emptyList(), 3)))
    }
}
