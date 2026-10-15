package dev.finalish.ui.home

import dev.finalish.ui.container
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.finalish.R
import dev.finalish.core.model.Project
import dev.finalish.core.util.Format
import dev.finalish.ui.components.RemoteImage
import dev.finalish.ui.components.pressable
import dev.finalish.ui.components.shimmer
import dev.finalish.ui.theme.Palette

/** Deterministic gradient so projects without artwork still feel distinct. */
fun projectGradient(seed: String): Brush {
    // Mix the hash so similar ids ("p1", "p2") land far apart on the color wheel.
    var x = seed.hashCode().toLong() and 0xffffffffL
    x = (x xor (x ushr 16)) * 0x45d9f3bL and 0xffffffffL
    x = x xor (x ushr 16)
    val h = (x % 360).toFloat()
    return Brush.linearGradient(
        listOf(Color.hsl(h, 0.5f, 0.45f), Color.hsl((h + 40f) % 360f, 0.55f, 0.2f)),
    )
}

/**
 * The image decoded at a few dozen pixels and stretched back up: reads as frosted glass, without a
 * RenderEffect blur per layer (three per card made the Projects grid stutter). Works on every
 * Android version, and its tiny bitmap gets its own memory-cache entry.
 */
@Composable
private fun FrostedImage(url: String, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val key = dev.finalish.ui.components.stableImageKey(url)
    val request = androidx.compose.runtime.remember(key) {
        coil3.request.ImageRequest.Builder(context).data(url)
            .size(FROST_PX)
            .memoryCacheKey("$key#frost$FROST_PX")
            .diskCacheKey(key)
            .build()
    }
    coil3.compose.AsyncImage(request, null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
}

private const val FROST_PX = 48

/**
 * Project card (mockup "Progetti · I3"): the latest file is the cover, the next two peek above it
 * as frosted glass, and name, count, date and size sit on a
 * two-line glass bar inside the cover. Folders keep their tabbed mosaic, so the two read apart.
 */
@Composable
internal fun ProjectStack(
    name: String,
    meta: String,
    seed: String,
    image: String?,
    cover: dev.finalish.core.model.FolderPreview,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    restricted: Boolean = false,
    newComments: Int = 0,
) {
    val front = image ?: cover.thumbnails.firstOrNull()
    val backs = if (image != null) emptyList() else cover.thumbnails.drop(1).take(2)
    val edge = if (large) 14.dp else 11.dp
    Column(modifier) {
        Box(Modifier.fillMaxWidth().padding(top = edge)) {
            // Farthest card first; each is a thin frosted edge above the cover.
            backs.withIndex().reversed().forEach { (i, url) ->
                val inset = if (i == 0) 0.88f else 0.76f
                val shape = RoundedCornerShape(if (i == 0) 14.dp else 12.dp)
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = -edge * (i + 1) / 2)
                        .fillMaxWidth(inset)
                        .height(40.dp)
                        .clip(shape)
                        .background(Palette.Surface3)
                        .border(1.dp, Color.White.copy(alpha = if (i == 0) 0.22f else 0.16f), shape),
                ) {
                    FrostedImage(url, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.12f)))
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (large) 2.1f else 16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(projectGradient(seed))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp)),
            ) {
                if (front != null) RemoteImage(front, Modifier.fillMaxSize(), fallback = {})
                else Text(
                    Format.initials(name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
                )
                // A light sweep across the top corner: glass, not paper.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            0f to Color.White.copy(alpha = 0.14f), 0.3f to Color.White.copy(alpha = 0.03f), 0.5f to Color.Transparent,
                        ),
                    ),
                )
                if (restricted) {
                    Icon(
                        Icons.Rounded.Lock, stringResource(R.string.home_restricted), tint = Color.White,
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp).size(16.dp),
                    )
                }
                if (newComments > 0) {
                    Text(
                        pluralStringResource(R.plurals.home_new_comments, newComments, newComments),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xA60A0A0C))
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
                val barInset = 6.dp
                val barHeight = if (large) 50.dp else 42.dp
                val barRadius = if (large) 12.dp else 10.dp
                val barShape = RoundedCornerShape(barRadius)
                if (front != null) {
                    // Frosted glass under the bar: the cover again, card-sized and soft, shown only
                    // through the bar's rounded rectangle.
                    val clip = androidx.compose.runtime.remember { androidx.compose.ui.graphics.Path() }
                    FrostedImage(
                        front,
                        Modifier.matchParentSize().drawWithContent {
                            val inset = barInset.toPx()
                            val r = barRadius.toPx()
                            clip.rewind()
                            clip.addRoundRect(
                                androidx.compose.ui.geometry.RoundRect(
                                    inset, size.height - inset - barHeight.toPx(), size.width - inset, size.height - inset,
                                    androidx.compose.ui.geometry.CornerRadius(r),
                                ),
                            )
                            clipPath(clip) { this@drawWithContent.drawContent() }
                        },
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(barInset)
                        .fillMaxWidth()
                        .height(barHeight)
                        .clip(barShape),
                ) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(Color(0xFF14141A).copy(alpha = if (front != null) 0.36f else 0.72f))
                            .border(1.dp, Color.White.copy(alpha = 0.18f), barShape),
                    )
                    Row(
                        Modifier.fillMaxSize().padding(start = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                name,
                                style = if (large) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Second line: item count (stack glyph), then when and how big.
                            val count = cover.count?.takeIf { it > 0 }
                            if (count != null || meta.isNotEmpty()) {
                                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (count != null) {
                                        val countDescription = pluralStringResource(R.plurals.home_item_count, count, count)
                                        Icon(
                                            Icons.Rounded.Layers, null,
                                            tint = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(11.dp),
                                        )
                                        Text(
                                            "$count",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.75f),
                                            modifier = Modifier
                                                .padding(start = 3.dp)
                                                .semantics { contentDescription = countDescription },
                                        )
                                    }
                                    Text(
                                        if (count != null && meta.isNotEmpty()) " · $meta" else meta,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.75f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        Icon(
                            Icons.Rounded.MoreHoriz, stringResource(R.string.home_project_options),
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier
                                .padding(start = 2.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .pressable(onMenu, scaleTo = 0.85f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProjectCard(
    project: Project,
    accountId: String?,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    newComments: Int = 0,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cover by androidx.compose.runtime.produceState(dev.finalish.core.model.FolderPreview(), project.id, project.updatedAt, accountId) {
        if (accountId != null) {
            value = runCatching { dev.finalish.FinalishApp.container(context).repository.projectCover(accountId, project) }
                .getOrDefault(dev.finalish.core.model.FolderPreview())
        }
    }
    ProjectStack(
        name = project.name,
        meta = listOfNotNull(Format.relative(project.updatedAt).ifBlank { null }, Format.bytes(project.storage).takeIf { project.storage > 0 })
            .joinToString(" · "),
        seed = project.id,
        image = project.image,
        cover = cover,
        onMenu = onMenu,
        large = large,
        restricted = project.restricted == true,
        newComments = newComments,
        modifier = modifier
            .graphicsLayer { alpha = if (project.status == "inactive") 0.6f else 1f }
            .pressable(onClick, onLongClick = onMenu),
    )
}

@Composable
internal fun ProjectCardSkeleton() {
    Column {
        Spacer(Modifier.height(11.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).shimmer(RoundedCornerShape(16.dp)))
        Spacer(Modifier.height(9.dp))
        Box(Modifier.fillMaxWidth(0.4f).height(10.dp).shimmer())
    }
}
