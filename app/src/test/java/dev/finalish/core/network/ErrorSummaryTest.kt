package dev.finalish.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorSummaryTest {
    @Test fun keepsCodesAndTitlesOnly() {
        val body = """{"errors":[{"code":"invalid","title":"Name taken","detail":"A project called \"Client X\" exists"}]}"""
        assertEquals("invalid · Name taken", errorSummary(body))
    }

    @Test fun otherBodiesShowOnlyTheirLength() {
        assertEquals("(18 chars)", errorSummary("<html>oops</html>!"))
    }
}
