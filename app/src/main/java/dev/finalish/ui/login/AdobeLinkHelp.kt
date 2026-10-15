package dev.finalish.ui.login

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.finalish.R
import dev.finalish.ui.components.PrimaryButton
import dev.finalish.ui.components.SecondaryButton
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette

private const val SUPPORT_EMAIL = "support@frame.io"
private const val FRAMEIO_URL = "https://next.frame.io"
private const val V4_HELP_URL =
    "https://help.frame.io/en/articles/11534393-what-to-expect-when-updating-to-v4-a-comprehensive-guide-for-updating-your-account-on-a-free-pro-or-team-plan"

/** Login-screen entry point to [AdobeLinkHelpSheet]. */
@Composable
fun AdobeLinkHelpRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Surface1)
            .pressable(onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = Palette.Amber, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.login_link_help_entry),
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextTertiary, modifier = Modifier.size(16.dp))
    }
}

/**
 * Explains that only Frame.io V4 works (Legacy accounts must be moved first), then that
 * Frame.io accounts created with Google before the move to Adobe may have no Adobe ID linked:
 * Adobe then signs the user in, but Frame.io returns no accounts. Only Frame.io support can link them.
 *
 * [email] is the Adobe ID the user signed in with, when known (prefilled in the support email).
 * [onSignInAgain] is shown when there is a sign-in to retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdobeLinkHelpSheet(onDismiss: () -> Unit, email: String? = null, onSignInAgain: (() -> Unit)? = null) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface2,
    ) {
        AdobeLinkHelpContent(email, onSignInAgain, Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding())
    }
}

@Composable
fun AdobeLinkHelpContent(email: String?, onSignInAgain: (() -> Unit)?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Amber.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Link, null, tint = Palette.Amber, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.login_link_help_title), style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.login_link_help_body),
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextSecondary,
        )

        Spacer(Modifier.height(22.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface1).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Step(1, stringResource(R.string.login_link_help_step1))
            Step(2, stringResource(R.string.login_link_help_step2))
            Step(3, stringResource(R.string.login_link_help_step3))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.login_link_help_keep_google),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Green,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Green.copy(alpha = 0.12f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )

        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface1).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.login_v4_title), style = MaterialTheme.typography.titleSmall, color = Palette.Text)
            Text(stringResource(R.string.login_v4_body), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .pressable(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, V4_HELP_URL.toUri())) } })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.login_v4_learn_more), style = MaterialTheme.typography.labelLarge, color = Palette.AccentBright)
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = Palette.AccentBright, modifier = Modifier.size(14.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            stringResource(R.string.login_link_help_email_support),
            onClick = { emailSupport(context, email) },
            icon = Icons.Rounded.MailOutline,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            stringResource(R.string.login_link_help_open_frameio),
            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, FRAMEIO_URL.toUri())) } },
            modifier = Modifier.fillMaxWidth(),
        )
        if (onSignInAgain != null) {
            TextButton(onClick = onSignInAgain, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(stringResource(R.string.login_link_help_sign_in_again), color = Palette.TextSecondary)
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "$n",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Palette.AccentBright,
            modifier = Modifier.width(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
    }
}

private fun emailSupport(context: Context, email: String?) {
    val body = buildString {
        append("Hello,\n\nmy Frame.io account was created with Google and isn't linked to an Adobe ID, ")
        append("so signing in with Adobe shows no Frame.io accounts. Could you link it to an Adobe ID")
        if (!email.isNullOrBlank()) append(" for $email")
        append("?\n\nThank you")
    }
    val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri())
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, "Link my Frame.io account to an Adobe ID")
        .putExtra(Intent.EXTRA_TEXT, body)
    // No mail app: fall back to the Frame.io help centre.
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://help.frame.io".toUri())) } }
}
