package dev.finalish.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan refusals as a Free account gets them from the V4 API. */
class PlanLimitsTest {
    @Test fun everyRefusedFeatureIsFound() {
        val body = """{"errors":[{"detail":"expiration: feature(s) not included in plan: share_link_expiration"},""" +
            """{"detail":"passphrase: feature(s) not included in plan: password_protected_shares"}]}"""
        assertEquals(listOf("share_link_expiration", "password_protected_shares"), PlanLimits.featuresIn(body))
        assertEquals(listOf("comment_attachments"), PlanLimits.featuresIn("""{"errors":[{"detail":"comment_attachments not included in plan"}]}"""))
        assertTrue(PlanLimits.featuresIn("""{"errors":[{"detail":"name: can't be blank"}]}""").isEmpty())
    }

    @Test fun messageNamesThemInWords() {
        assertEquals(
            "Your Frame.io plan doesn't include expiring share links or passphrase-protected share links.",
            PlanLimits.message(listOf("share_link_expiration", "password_protected_shares")),
        )
        assertEquals("Your Frame.io plan doesn't include some new thing.", PlanLimits.message(listOf("some_new_thing")))
    }

    @Test fun accountFromPath() {
        assertEquals("a1", PlanLimits.accountOf(listOf("v4", "accounts", "a1", "projects", "p", "shares")))
    }

    @Test fun freeToldByStorage() {
        assertTrue(PlanLimits.isFree(2_000_000_000))
        assertTrue(!PlanLimits.isFree(2_000_000_000_000))
        assertTrue(!PlanLimits.isFree(null))
        PlanLimits.seed("free-acc", 2_000_000_000)
        assertTrue(PlanLimits.lacks("free-acc", "share_link_expiration"))
        PlanLimits.seed("pro-acc", 2_000_000_000_000)
        assertTrue(!PlanLimits.lacks("pro-acc", "share_link_expiration"))
        assertEquals(2, PlanLimits.projectLimit("free-acc"))
        assertEquals(null, PlanLimits.projectLimit("pro-acc"))
    }

    @Test fun recordedPerAccount() {
        PlanLimits.record("acc-test", listOf("secure_sharing"))
        assertTrue(PlanLimits.lacks("acc-test", "secure_sharing"))
        assertTrue(!PlanLimits.lacks("other", "secure_sharing"))
    }
}
