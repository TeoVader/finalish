package dev.finalish.core.notify

import android.app.Application
import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import dev.finalish.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PrivateNotificationTest {
    @Test fun publicVersionDoesNotExposePrivateContent() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        listOf(R.string.core_notify_public_activity, R.string.core_notify_public_transfer).forEach { text ->
            val notification = NotificationCompat.Builder(context, "test")
                .setSmallIcon(R.drawable.ic_stat_finalish)
                .setContentTitle("Private file")
                .setContentText("Author: private comment")
                .privateOnLockscreen(context, "test", text)
                .build()
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            val public = notification.publicVersion!!
            assertEquals(context.getString(text), public.extras.getString(Notification.EXTRA_TITLE))
            assertNull(public.extras.getString(Notification.EXTRA_TEXT))
            assertNull(public.contentIntent)
            assertTrue(public.actions.isNullOrEmpty())
        }
    }
}
