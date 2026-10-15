package dev.finalish.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import dev.finalish.R
import dev.finalish.ui.shares.SimpleTopBar
import dev.finalish.ui.theme.Palette

/** Settings › Open-source licenses: every library in the build (generated at build time) and the demo media credits. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    LicensesScreen(libraries, onBack)
}

@Composable
fun LicensesScreen(libraries: Libs?, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.Ink)) {
        SimpleTopBar(stringResource(R.string.settings_open_source_licenses), null, onBack) {}
        Text(
            stringResource(R.string.settings_demo_media, stringResource(DEMO_MEDIA_CREDITS)),
            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LibrariesContainer(libraries, Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp))
    }
}
