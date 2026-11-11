package dev.finalish.core.notify

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import dev.finalish.R

/** The public version deliberately has no file names, deep links or actions. */
internal fun NotificationCompat.Builder.privateOnLockscreen(
    context: Context,
    channel: String,
    @StringRes publicText: Int,
): NotificationCompat.Builder = setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    .setPublicVersion(
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_finalish)
            .setContentTitle(context.getString(publicText))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build(),
    )
