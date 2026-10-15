package dev.finalish.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.finalish.R
import dev.finalish.core.media.OfficeDoc
import dev.finalish.core.media.OfficePreview
import dev.finalish.core.model.Asset
import dev.finalish.ui.theme.Palette
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Word, Excel and PowerPoint read on the phone (Frame.io keeps no preview of them). */
@Composable
fun OfficeStage(
    file: Asset,
    offlinePath: String?,
    modifier: Modifier = Modifier,
    onOpenWith: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val ext = file.media.name.substringAfterLast('.', "").lowercase()
    // The viewer first shows the folder's copy of the file, which has no original link yet:
    // wait for the full one rather than failing. null: loading; failure falls back to "open with".
    val original = file.mediaLinks?.original?.let { it.downloadUrl ?: it.inlineUrl }
    val doc by produceState<Result<OfficeDoc>?>(null, file.id, offlinePath, original != null) {
        if (original == null && offlinePath == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val local = offlinePath?.let(::File)?.takeIf { it.exists() } ?: File(context.cacheDir, "office/${file.id}.$ext").also { target ->
                    if (!target.exists()) {
                        target.parentFile?.mkdirs()
                        val url = original ?: error("No original")
                        val http = dev.finalish.FinalishApp.container(context).baseHttp
                        // Into a .part first: a cut-off download never passes for the file on the next open.
                        val part = File(target.parentFile, target.name + ".part")
                        try {
                            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                                if (!r.isSuccessful) error("HTTP ${r.code}")
                                r.body.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                            }
                            if (!part.renameTo(target)) error("Could not save the file")
                        } finally {
                            part.delete()
                        }
                    }
                }
                OfficePreview.read(local, ext) ?: error("Unreadable")
            }.onFailure { dev.finalish.core.util.AppLog.w("Office preview (.$ext) failed", it) }
        }
    }
    when (val d = doc?.getOrNull()) {
        null -> if (doc == null) {
            Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp) }
        } else OtherStage(file, modifier, onOpenWith, onDownload)
        is OfficeDoc.Document -> DocumentView(d, modifier)
        is OfficeDoc.Sheet -> SheetView(d, modifier)
        is OfficeDoc.Slides -> SlidesView(d, modifier)
    }
}

/** A page of paper: dark text on white, like the document itself. */
@Composable
internal fun DocumentView(d: OfficeDoc.Document, modifier: Modifier = Modifier) {
    val pages = remember(d) {
        d.blocks.fold(mutableListOf(mutableListOf<OfficeDoc.Block>())) { acc, b ->
            if (b.style == OfficeDoc.Block.Style.PageBreak) acc += mutableListOf<OfficeDoc.Block>() else acc.last() += b
            acc
        }.filter { it.isNotEmpty() }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(pages) { i, blocks ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color.White).padding(horizontal = 22.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                blocks.forEach { b ->
                    when (b.style) {
                        OfficeDoc.Block.Style.Title -> Text(b.text, color = Color(0xFF111114), fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
                        OfficeDoc.Block.Style.Heading -> Text(b.text, color = Color(0xFF111114), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                        OfficeDoc.Block.Style.Bullet -> Row { Text("•  ", color = Color(0xFF333338), fontSize = 14.sp); Text(b.text, color = Color(0xFF333338), fontSize = 14.sp, lineHeight = 20.sp) }
                        else -> Text(b.text, color = Color(0xFF333338), fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
                Text(stringResource(R.string.viewer_page_counter, i + 1, pages.size), color = Color(0xFF9A9AA2), fontSize = 11.sp, modifier = Modifier.align(Alignment.End).padding(top = 8.dp))
            }
        }
    }
}

/** The first sheet as a grid, with column letters and row numbers. */
@Composable
internal fun SheetView(s: OfficeDoc.Sheet, modifier: Modifier = Modifier) {
    val cols = s.rows.maxOfOrNull { it.size } ?: 0
    val cell = 112.dp
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text(
            listOfNotNull(s.name, s.otherSheets.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.viewer_more_sheets, it, it) }, stringResource(R.string.viewer_first_rows_only).takeIf { s.truncated }).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary, modifier = Modifier.padding(bottom = 8.dp),
        )
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).border(1.dp, Palette.Hairline, RoundedCornerShape(10.dp)).horizontalScroll(rememberScrollState())) {
            LazyColumn {
                item {
                    Row(Modifier.background(Palette.Surface3)) {
                        Box(Modifier.width(40.dp).height(30.dp))
                        repeat(cols) { c -> Header(('A' + c).toString(), cell) }
                    }
                }
                itemsIndexed(s.rows) { r, row ->
                    Row(Modifier.background(if (r % 2 == 0) Palette.Surface1 else Palette.Ink)) {
                        Header("${r + 1}", 40.dp)
                        repeat(cols) { c ->
                            Text(
                                row.getOrNull(c).orEmpty(),
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(cell).padding(horizontal = 8.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String, width: androidx.compose.ui.unit.Dp) {
    Box(Modifier.width(width).height(30.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
    }
}

/** One 16:9 card per slide with its text. */
@Composable
internal fun SlidesView(s: OfficeDoc.Slides, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(s.slides) { i, lines ->
            Column {
                Text(stringResource(R.string.viewer_slide_number, i + 1), style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary, modifier = Modifier.padding(bottom = 6.dp))
                Column(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    lines.forEachIndexed { k, line ->
                        Text(
                            line, color = Color(0xFF111114),
                            fontSize = if (k == 0) 18.sp else 13.sp, fontWeight = if (k == 0) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = if (k == 0) 2 else 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (lines.isEmpty()) Text(stringResource(R.string.viewer_slide_no_text), color = Color(0xFF9A9AA2), fontSize = 13.sp)
                }
            }
        }
    }
}
