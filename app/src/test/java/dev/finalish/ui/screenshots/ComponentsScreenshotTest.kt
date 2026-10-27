package dev.finalish.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.finalish.core.model.User
import dev.finalish.ui.components.Avatar
import dev.finalish.ui.components.BrandMark
import dev.finalish.ui.components.EmptyState
import dev.finalish.ui.components.ErrorState
import dev.finalish.ui.components.StatusPill
import dev.finalish.ui.components.TransferProgress
import dev.finalish.ui.login.AdobeLinkHelpContent
import dev.finalish.ui.login.AdobeLinkHelpRow
import dev.finalish.ui.onboarding.BrandLockup
import dev.finalish.ui.theme.FinalishTheme
import dev.finalish.ui.theme.Palette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests of the design-system pieces, compared against the mockup.
 * Record: ./gradlew :app:recordRoborazziDebug   Verify: ./gradlew :app:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Same proportions as the Pixel 11 Pro XL (1344×2992 px): every screenshot is a full phone screen.
@Config(sdk = [35], qualifiers = "w448dp-h997dp-night-xxhdpi", application = android.app.Application::class)
class ComponentsScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private fun snap(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false // freeze infinite animations (wave, shimmer)
        compose.setContent {
            FinalishTheme {
                Column(Modifier.fillMaxSize().background(Palette.Ink).statusBarsPadding().padding(16.dp)) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun brand() = snap("brand") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BrandMark(72.dp)
            BrandMark(40.dp)
        }
        BrandLockup(Modifier.padding(top = 16.dp))
    }

    @Test fun avatarsAndStatus() = snap("avatars_status") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(User(id = "1", name = "Noa Lindqvist"), 34.dp)
            Avatar(User(id = "2", name = "Sam"), 34.dp)
            Avatar(null, 34.dp)
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("Approved")
            StatusPill("Needs review")
            StatusPill("In progress")
        }
    }

    @Test fun progress() = snap("progress") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TransferProgress(0.6f, Modifier.fillMaxWidth())
            TransferProgress(0.35f, Modifier.fillMaxWidth(), moving = false)
        }
    }

    @Test fun emptyAndError() = snap("empty_error") {
        EmptyState(Icons.Rounded.FolderOpen, "Nothing here yet", "Upload files or create a folder.", actionLabel = "Upload", onAction = {})
        ErrorState("Check your connection and try again.", onRetry = {})
    }

    @Test fun adobeLinkHelp() = snap("adobe_link_help") {
        AdobeLinkHelpRow(onClick = {})
        Column(Modifier.padding(top = 16.dp).background(Palette.Surface2).padding(top = 20.dp)) {
            AdobeLinkHelpContent(email = "noa@example.com", onSignInAgain = {})
        }
    }

    /** Versions (mockup): newest first, the head marked Current, ⋯ per version, + New version. */
    private fun comments(n: Int) = dev.finalish.core.model.MetadataField(fieldType = "number", name = "Comment Count", value = kotlinx.serialization.json.JsonPrimitive(n.toDouble()))

    @Test fun versions() = snap("versions") {
        val me = dev.finalish.core.model.User(id = "u", name = "Noa Lindqvist")
        val versions = listOf(
            dev.finalish.core.model.Asset("v1", name = "Full Spot - Offline edit.mp4", mediaType = "video/mp4", creator = me, metadata = listOf(comments(12))),
            dev.finalish.core.model.Asset("v2", name = "Full Spot - Graded.mp4", mediaType = "video/mp4", creator = me, metadata = listOf(comments(3))),
        )
        androidx.compose.foundation.layout.Box(Modifier.height(560.dp)) {
            dev.finalish.ui.viewer.VersionsPanel(versions, "v1", {}, { _, _ -> }, onReorder = { _, _, _ -> }, onNewVersion = {})
        }
    }

    /** Transfers › Downloads: a folder on its way, a file, a folder with a failed file, and a finished file. */
    @Test fun downloadsInTransfers() = snap("downloads_transfers") {
        val s = dev.finalish.core.transfer.TransferState.Running
        fun d(name: String, state: dev.finalish.core.transfer.TransferState, folder: Boolean, saved: Int = 0, failed: Int = 0, skipped: Int = 0, done: Long = 0, total: Long = 4_300_000_000, kind: dev.finalish.core.model.MediaKind = dev.finalish.core.model.MediaKind.Video) =
            dev.finalish.core.transfer.DeviceDownload(
                id = name, accountId = "a", name = name, folder = folder, path = if (folder) "Finalish/$name" else "Finalish",
                kind = kind, files = if (folder) 40 else 1, saved = saved, failed = failed, skipped = skipped,
                failedItems = List(failed) { dev.finalish.core.transfer.PlannedDownload("x$it", "x", 1) },
                totalBytes = total, doneBytes = done, state = state,
            )
        Column(Modifier.padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            dev.finalish.ui.transfers.DeviceDownloadRow(d("Day 2 rushes", s, true, saved = 12, done = 1_300_000_000), {}, {}, {}, Modifier)
            dev.finalish.ui.transfers.DeviceDownloadRow(d("Edit_v3.mov", s, false, done = 820_000_000, total = 1_900_000_000), {}, {}, {}, Modifier)
            dev.finalish.ui.transfers.DeviceDownloadRow(d("Brand film", dev.finalish.core.transfer.TransferState.Done, true, saved = 38, skipped = 1, failed = 1), {}, {}, {}, Modifier)
            dev.finalish.ui.transfers.DeviceDownloadRow(d("Moodboard.png", dev.finalish.core.transfer.TransferState.Done, false, saved = 1, total = 8_100_000, kind = dev.finalish.core.model.MediaKind.Image), {}, {}, {}, Modifier)
        }
    }

    /** "Import from link" in Transfers: Frame.io fetching it, done, and failed. */
    @Test fun importsInTransfers() = snap("imports_transfers") {
        fun item(name: String, state: dev.finalish.core.transfer.TransferState, error: String? = null) = dev.finalish.core.transfer.UploadItem(
            id = name, accountId = "a", folderId = "f", folderName = "Locations", uri = "https://example.com/$name",
            name = name, mimeType = "", totalBytes = 0, state = state, error = error,
            remoteFileId = "r", remoteSource = "https://example.com/$name",
        )
        Column(Modifier.padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            dev.finalish.ui.transfers.UploadRow(item("Drone_B-roll.mov", dev.finalish.core.transfer.TransferState.Running), {}, {}, Modifier)
            dev.finalish.ui.transfers.UploadRow(item("Scouting_day2.mp4", dev.finalish.core.transfer.TransferState.Done), {}, {}, Modifier)
            dev.finalish.ui.transfers.UploadRow(item("Interview_raw.mxf", dev.finalish.core.transfer.TransferState.Failed, "Frame.io couldn't fetch the file from the link"), {}, {}, Modifier)
        }
    }

    /** Share link › Watermark template: default plus the account's templates. */
    @Test fun watermarkTemplates() = snap("watermark_templates") {
        val wm = dev.finalish.core.model.WatermarkTemplate(
            "a", "Client review",
            listOf(dev.finalish.core.model.WatermarkBlock("bottom_right", listOf(
                dev.finalish.core.model.WatermarkDataPoint("email", order = 1.0), dev.finalish.core.model.WatermarkDataPoint("timestamp", order = 2.0),
            ))),
        )
        val conf = dev.finalish.core.model.WatermarkTemplate(
            "b", "Confidential",
            listOf(dev.finalish.core.model.WatermarkBlock("middle_center", listOf(dev.finalish.core.model.WatermarkDataPoint("custom_text", "CONFIDENTIAL", 1.0)))),
        )
        Column(Modifier.background(Palette.Surface1).padding(top = 12.dp)) {
            dev.finalish.ui.shares.WatermarkTemplateList(listOf(wm, conf), "a") {}
        }
    }

    /** Settings › Metadata fields: custom fields, then the built-in ones. */
    @Test fun metadataFields() = snap("metadata_fields") {
        fun select(id: String, name: String, type: String, vararg o: String) = dev.finalish.core.model.FieldDefinition(
            id, name, type,
            configuration = kotlinx.serialization.json.buildJsonObject {
                put("options", kotlinx.serialization.json.JsonArray(o.map { kotlinx.serialization.json.buildJsonObject { put("display_name", kotlinx.serialization.json.JsonPrimitive(it)) } }))
            },
        )
        val fields = listOf(
            select("1", "Scene", "select", "Exterior", "Interior", "Day", "Night"),
            dev.finalish.core.model.FieldDefinition("2", "Take", "number"),
            select("3", "Deliverables", "select_multi", "Web", "TV", "Social"),
            dev.finalish.core.model.FieldDefinition("4", "Approved by", "user_single"),
            dev.finalish.core.model.FieldDefinition("5", "Status", "select", mutable = false),
            dev.finalish.core.model.FieldDefinition("6", "Rating", "rating", mutable = false),
            dev.finalish.core.model.FieldDefinition("7", "Notes", "long_text", mutable = false),
        )
        dev.finalish.ui.settings.MetadataFieldList(fields) {}
    }

    /** New field sheet with a single select and its options. */
    @Test fun newMetadataField() = snap("metadata_field_new") {
        Column(Modifier.background(Palette.Surface1).padding(top = 16.dp)) {
            dev.finalish.ui.settings.NewFieldForm({ _, _, _ -> }, initialType = "select", initialName = "Scene", initialOptions = listOf("Exterior", "Interior"))
        }
    }

    /** Settings › Groups, a group's people, and groups with a role in Members. */
    @Test fun userGroups() = snap("user_groups") {
        val editors = dev.finalish.core.model.UserGroup("1", "Editors", "✂️", 4)
        val client = dev.finalish.core.model.UserGroup("2", "Client", "🤝", 2)
        val sound = dev.finalish.core.model.UserGroup("3", "Sound team", "🎧", 3, source = "adobe")
        Column {
            androidx.compose.foundation.layout.Box(Modifier.height(330.dp)) { dev.finalish.ui.members.GroupList(listOf(client, editors, sound)) {} }
            dev.finalish.ui.members.GroupRoleRow(dev.finalish.core.model.GroupRole(editors, "editor"), "projects", {}, {})
            dev.finalish.ui.members.GroupRoleRow(dev.finalish.core.model.GroupRole(client, "commenter"), "projects", {}, {})
            androidx.compose.foundation.layout.Box(Modifier.height(260.dp)) {
                dev.finalish.ui.members.GroupPeople(
                    editors,
                    listOf(
                        dev.finalish.core.model.User("u1", name = "Giulia Bianchi", email = "giulia@example.com"),
                        dev.finalish.core.model.User("u2", name = "Marco Rossi", email = "marco@example.com"),
                    ),
                ) {}
            }
        }
    }

    /** New group sheet: name and emoji. */
    @Test fun newGroup() = snap("group_new") {
        Column(Modifier.background(Palette.Surface1).padding(top = 16.dp)) {
            dev.finalish.ui.members.GroupNameForm("New group", "Create group", { _, _ -> }, initialName = "Editors", initialEmoji = "✂️")
        }
    }

    /** Offline folder: saved files stay bright, the rest fade back and say they need the network. */
    @Test fun offlineFiles() = snap("offline_files") {
        dev.finalish.ui.components.OfflineBannerContent()
        val files = listOf(
            dev.finalish.core.model.Asset("1", name = "Full Spot - Graded.mp4", fileSize = 412_000_000, mediaType = "video/mp4") to true,
            dev.finalish.core.model.Asset("2", name = "60 Second Cut.mp4", fileSize = 310_000_000, mediaType = "video/mp4") to false,
            dev.finalish.core.model.Asset("3", name = "Sopralluogo_01.jpg", fileSize = 3_000_000, mediaType = "image/jpeg") to false,
        )
        Column(Modifier.padding(top = 12.dp)) {
            files.forEach { (a, saved) ->
                dev.finalish.ui.browser.AssetListRow(a, selected = false, selecting = false, offline = saved, onClick = {}, onLongClick = {}, online = false)
            }
        }
    }

    /** "Share to Final(ish)": three gallery files to last time's folder, then one file stacked as a new version. */
    @Test fun shareIn() = snap("share_in") {
        val files = listOf(
            dev.finalish.ui.home.SharedFile("content://x/1", "video/mp4", 120_000_000),
            dev.finalish.ui.home.SharedFile("content://x/2", "image/jpeg", 4_000_000),
            dev.finalish.ui.home.SharedFile("content://x/3", "audio/wav", 90_000_000),
        )
        Column(Modifier.background(Palette.Surface1).padding(top = 20.dp)) {
            dev.finalish.ui.home.ShareInContent(files, "Gallery", "Demo Project › Locations", null, {}, {}, {}, {})
        }
        Column(Modifier.padding(top = 16.dp).background(Palette.Surface1).padding(top = 20.dp)) {
            dev.finalish.ui.home.ShareInContent(files.take(1), null, "Demo Project › Cuts", "Teaser_v3.mov", {}, {}, {}, {})
        }
    }

    /** The launcher icons for "Matching app icon", one per accent (as the adaptive icon's 66% safe zone). */
    @Test fun projectCovers() = snap("project_covers") {
        // Preview handler: each fake URL draws as a flat colour instead of loading.
        val colors = mapOf("a" to 0xFF3D4F7A, "b" to 0xFFC9B48E, "c" to 0xFFB84A4A, "d" to 0xFF9BD36A, "e" to 0xFFCFC6B8)
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalInspectionMode provides true,
            coil3.compose.LocalAsyncImagePreviewHandler provides coil3.compose.AsyncImagePreviewHandler { r ->
                coil3.ColorImage(colors[r.data.toString()]?.toInt() ?: 0xFF444444.toInt())
            },
        ) {
            // Phone width: two columns, as in the Projects grid. A full stack, a two-file project, one with no files yet.
            val cards = listOf(
                Triple("Mosswood Pictures", dev.finalish.core.model.FolderPreview(listOf("a", "b", "c"), 27), "3d ago · 42.3 GB"),
                Triple("Saltmarsh", dev.finalish.core.model.FolderPreview(listOf("d", "e"), 2), "5d ago · 2.1 GB"),
                Triple("New project", dev.finalish.core.model.FolderPreview(), ""),
            )
            androidx.compose.foundation.layout.Column(
                Modifier.width(390.dp).padding(16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
            ) {
                cards.chunked(2).forEach { row ->
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                        row.forEach { (name, cover, meta) ->
                            dev.finalish.ui.home.ProjectStack(
                                name = name, meta = meta, seed = name, image = null, cover = cover, onMenu = {},
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    @Test fun notifications() = snap("notifications") {
        fun e(id: String, kind: dev.finalish.core.notify.ActivityEvent.Kind, title: String, actors: List<String>, count: Int, at: String, read: Boolean = false, text: String? = null) =
            dev.finalish.core.notify.ActivityEvent(id, kind, "a", "p1", title, fileId = "f$id", actors = actors, count = count, text = text, at = at, read = read)
        dev.finalish.ui.notifications.NotificationList(
            listOf(
                e("1", dev.finalish.core.notify.ActivityEvent.Kind.Comments, "Saltmarsh_Grade_v2.mp4", listOf("Tomás Okafor"), 2, "2025-10-06T14:32:00Z"),
                e("2", dev.finalish.core.notify.ActivityEvent.Kind.Mention, "Kites_Cut_v4.mp4", listOf("Ines Varga"), 1, "2025-09-28T09:10:00Z", text = "@Noa Lindqvist can you check the grade here?"),
                e("3", dev.finalish.core.notify.ActivityEvent.Kind.Replies, "Kites_Cut_v4.mp4", listOf("Ines Varga", "Tomás Okafor"), 3, "2025-09-28T08:05:00Z"),
                e("4", dev.finalish.core.notify.ActivityEvent.Kind.UploadReady, "Teaser_v3.mov", emptyList(), 1, "2025-09-27T18:40:00Z", read = true),
                e("5", dev.finalish.core.notify.ActivityEvent.Kind.ShareViewed, "Client review", listOf("Kai Brennan"), 4, "2025-09-27T11:00:00Z", read = true),
            ),
            mapOf("p1" to "Saltmarsh"),
        ) {}
    }

    @Test fun backgroundLimitHint() = snap("background_limit_hint") {
        dev.finalish.ui.notifications.BackgroundLimitHint(onOpenSettings = {})
    }

    @Test fun officePreviews() {
        fun read(name: String, ext: String) =
            dev.finalish.core.media.OfficePreview.read(java.io.File(javaClass.classLoader!!.getResource("office/$name")!!.toURI()), ext)!!
        val doc = read("sample.docx", "docx") as dev.finalish.core.media.OfficeDoc.Document
        val sheet = read("sample.xlsx", "xlsx") as dev.finalish.core.media.OfficeDoc.Sheet
        val slides = read("sample.pptx", "pptx") as dev.finalish.core.media.OfficeDoc.Slides
        snap("office_previews") {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(380.dp)) { dev.finalish.ui.viewer.DocumentView(doc) }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(220.dp)) { dev.finalish.ui.viewer.SheetView(sheet) }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(340.dp)) { dev.finalish.ui.viewer.SlidesView(slides) }
        }
    }

    /** Settings › Open-source licenses, from the list the AboutLibraries plugin generates. */
    @Test fun licenses() = snap("licenses") {
        // Loaded up front: the screen's own async load would make the capture racy.
        val json = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .resources.openRawResource(dev.finalish.R.raw.aboutlibraries).bufferedReader().use { it.readText() }
        val libs = com.mikepenz.aboutlibraries.Libs.Builder().withJson(json).build()
        dev.finalish.ui.settings.LicensesScreen(libs, onBack = {})
    }

    /** Tablet (expanded width): the home bar stands on the left; text-heavy tabs keep a readable width. */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-night-xhdpi")
    fun tabletHome() = snap("tablet_home") {
        val json = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .resources.openRawResource(dev.finalish.R.raw.aboutlibraries).bufferedReader().use { it.readText() }
        val libs = com.mikepenz.aboutlibraries.Libs.Builder().withJson(json).build()
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            dev.finalish.ui.home.FloatingNav(current = 3, badge = 1, onSelect = {}, vertical = true, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterStart))
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(start = 88.dp), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                androidx.compose.foundation.layout.Box(Modifier.widthIn(max = 720.dp)) { dev.finalish.ui.settings.LicensesScreen(libs, onBack = {}) }
            }
        }
    }

    /** Intro art: the three steps once their light entrance has played. */
    @Test
    @Config(qualifiers = "w1100dp-h500dp-night-xhdpi")
    fun introArt() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FinalishTheme {
                Row(Modifier.fillMaxSize().background(Palette.Ink).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { i ->
                        androidx.compose.foundation.layout.Box(
                            Modifier.weight(1f).height(340.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(Palette.Surface1),
                        ) { dev.finalish.ui.onboarding.IntroArt(i) }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(3000) // past the entrance animations (the upload is still going)
        compose.onRoot().captureRoboImage("src/test/screenshots/intro_art.png")
    }

    /** Share link with several files: a mosaic of the first few, "+N" for the rest (3, 5 and 8 files). */
    @Test fun shareMosaic() = snap("share_mosaic") {
        val share = dev.finalish.core.model.Share("s1", name = "Saltmarsh")
        fun files(n: Int) = List(n) { i -> dev.finalish.core.model.Asset("f$i", name = "Shot_${i + 1}.mp4", mediaType = if (i % 2 == 0) "video/mp4" else "image/jpeg") }
        listOf(3, 5, 8).forEach { n ->
            dev.finalish.ui.shares.ShareMosaic(null, share, files(n), Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)))
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        }
    }

    /** Report form: the Frame.io plan the reporter is on (Italian, unanswered and answered). */
    @Test
    @Config(qualifiers = "it-w448dp-h997dp-night-xxhdpi")
    fun feedbackPlan() = snap("feedback_plan_it") {
        dev.finalish.ui.feedback.PlanLine(dev.finalish.core.feedback.FramePlan.Unknown) {}
        dev.finalish.ui.feedback.PlanLine(dev.finalish.core.feedback.FramePlan.Free) {}
    }

    /** Phone in landscape (compact height): the home bar moves to the left side, as on tablets. */
    @Test
    @Config(qualifiers = "w731dp-h411dp-night-xxhdpi")
    fun phoneLandscapeHome() = snap("phone_landscape_home") {
        val json = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .resources.openRawResource(dev.finalish.R.raw.aboutlibraries).bufferedReader().use { it.readText() }
        val libs = com.mikepenz.aboutlibraries.Libs.Builder().withJson(json).build()
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            dev.finalish.ui.home.FloatingNav(current = 2, badge = 1, onSelect = {}, vertical = true, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterStart))
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(start = 88.dp)) { dev.finalish.ui.settings.LicensesScreen(libs, onBack = {}) }
        }
    }

    /** Tablet: a secondary screen (notifications) keeps a readable column instead of stretching. */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-night-xhdpi")
    fun tabletNotifications() = snap("tablet_notifications") {
        fun e(id: String, kind: dev.finalish.core.notify.ActivityEvent.Kind, title: String, actors: List<String>, count: Int, at: String, read: Boolean = false) =
            dev.finalish.core.notify.ActivityEvent(id, kind, "a", "p1", title, fileId = "f$id", actors = actors, count = count, at = at, read = read)
        dev.finalish.ui.components.ReadableScreen {
            Column {
                dev.finalish.ui.shares.SimpleTopBar("Notifications", null, onBack = {})
                dev.finalish.ui.notifications.NotificationList(
                    listOf(
                        e("1", dev.finalish.core.notify.ActivityEvent.Kind.Comments, "Saltmarsh_Grade_v2.mp4", listOf("Tomás Okafor"), 2, "2025-10-06T14:32:00Z"),
                        e("2", dev.finalish.core.notify.ActivityEvent.Kind.Replies, "Kites_Cut_v4.mp4", listOf("Ines Varga", "Tomás Okafor"), 3, "2025-09-28T08:05:00Z"),
                        e("3", dev.finalish.core.notify.ActivityEvent.Kind.UploadReady, "Teaser_v3.mov", emptyList(), 1, "2025-09-27T18:40:00Z", read = true),
                    ),
                    mapOf("p1" to "Saltmarsh"),
                ) {}
            }
        }
    }

    /** Tablet (portrait): the intro keeps phone width, art and text together in the middle. */
    @Test
    @Config(qualifiers = "w800dp-h1280dp-night-xhdpi")
    fun tabletOnboarding() {
        compose.mainClock.autoAdvance = false
        compose.setContent { FinalishTheme { dev.finalish.ui.onboarding.IntroPager(onDone = {}, onDemo = {}) } }
        compose.mainClock.advanceTimeBy(3000) // past the art's entrance
        compose.onRoot().captureRoboImage("src/test/screenshots/tablet_onboarding.png")
    }

    /** Italian: the open pill grows to fit long labels ("Trasferimenti", "Impostazioni"). */
    @Test
    @Config(qualifiers = "it-w448dp-h997dp-night-xxhdpi")
    fun floatingNavItalian() = snap("floating_nav_it") {
        dev.finalish.ui.home.FloatingNav(current = 2, badge = 0, onSelect = {})
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        dev.finalish.ui.home.FloatingNav(current = 3, badge = 0, onSelect = {})
    }

    /** Comment field in Italian: phone width, then a 400dp side panel where the timecode moves above it. */
    @Test
    @Config(qualifiers = "it-w600dp-h600dp-night-xxhdpi")
    fun commentComposerNarrow() = snap("comment_composer_it") {
        listOf(448.dp, 400.dp).forEach { w ->
            dev.finalish.ui.viewer.CommentComposer(
                composer = dev.finalish.ui.viewer.ComposerState(),
                timed = true, currentTime = { "00:00:12:08" }, rangeLabel = { null },
                onText = {}, onToggleTime = {}, onToggleRange = {}, onToggleDraw = {}, onTogglePin = {}, onClearPin = {},
                onTool = {}, onColor = {}, onUndo = {}, onCancelEdit = {}, onSend = {},
                modifier = Modifier.width(w),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
        }
    }

    /** A comment with replies: "Reply" opens the file on Frame.io (the public API can't post replies). */
    @Test fun commentReplies() = snap("comment_replies") {
        val noa = User(id = "1", name = "Noa Lindqvist")
        val mira = User(id = "2", name = "Mira Solberg")
        val comment = dev.finalish.core.model.Comment(
            id = "c1", text = "Can we hold the wide shot a beat longer?", owner = noa,
            timestamp = kotlinx.serialization.json.JsonPrimitive(290),
            replies = listOf(dev.finalish.core.model.Comment(id = "r1", text = "Sure, adding 12 frames.", owner = mira)),
        )
        dev.finalish.ui.viewer.CommentsList(
            state = dev.finalish.ui.viewer.ViewerState(
                file = dev.finalish.core.model.Asset("f1", name = "Saltmarsh_cut_v3.mp4", viewUrl = "https://next.frame.io/project/p1/view/f1"),
                loading = false, comments = listOf(comment), commentsLoading = false, me = mira,
            ),
            listState = androidx.compose.foundation.lazy.rememberLazyListState(),
            timeOf = { "00:00:12:02" },
            onSelect = {}, onToggleComplete = {}, onEdit = {}, onDelete = {}, onFilter = {},
        )
    }

    @Test fun appIcons() = snap("app_icons") {
        val icons = listOf(
            "Wisteria" to dev.finalish.R.drawable.ic_launcher_foreground,
            "Lavender" to dev.finalish.R.drawable.ic_launcher_foreground_lavanda,
            "Amber" to dev.finalish.R.drawable.ic_launcher_foreground_ambra,
            "Lime" to dev.finalish.R.drawable.ic_launcher_foreground_lime,
            "Mint" to dev.finalish.R.drawable.ic_launcher_foreground_menta,
            "Coral" to dev.finalish.R.drawable.ic_launcher_foreground_corallo,
            "Cyan" to dev.finalish.R.drawable.ic_launcher_foreground_ciano,
            "Black & white" to dev.finalish.R.drawable.ic_launcher_foreground_mono,
        )
        icons.chunked(2).forEach { row ->
            Row(Modifier.padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { (label, res) ->
                    Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.size(120.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(0xFF0A0A0C)),
                        ) {
                            androidx.compose.foundation.Image(
                                androidx.compose.ui.res.painterResource(res), label,
                                Modifier.size(120.dp).graphicsLayer { scaleX = 1.5f; scaleY = 1.5f },
                            )
                        }
                        androidx.compose.material3.Text(label, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }

    @Test fun folderUploadSheet() = snap("folder_upload_sheet") {
        val f = { name: String, size: Long -> dev.finalish.core.transfer.LocalFile("content://x/$name", name, size) }
        val root = dev.finalish.core.transfer.LocalFolder(
            name = "Day 2 rushes",
            files = listOf(f("A001.mov", 2_100_000_000), f("A002.mov", 1_800_000_000)),
            folders = listOf(dev.finalish.core.transfer.LocalFolder("Sound", files = listOf(f("Boom.wav", 410_000_000)))),
        )
        val uri = android.net.Uri.parse("content://x/tree")
        androidx.compose.foundation.layout.Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(Palette.Surface2).padding(top = 20.dp)) {
            dev.finalish.ui.browser.FolderUploadContent(
                dev.finalish.ui.browser.FolderUploadUi.Ready(
                    uri, root, newName = "Day 2 rushes (2)",
                    existing = dev.finalish.core.transfer.FolderTree("Day 2 rushes", files = listOf(dev.finalish.core.transfer.RemoteFile("r", "A001.mov", 2_100_000_000)), id = "R"),
                ),
                "Brand film", {}, {}, {}, {},
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        androidx.compose.foundation.layout.Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(Palette.Surface2).padding(top = 20.dp)) {
            dev.finalish.ui.browser.FolderUploadContent(dev.finalish.ui.browser.FolderUploadUi.Scanning(uri, 12), "Brand film", {}, {}, {}, {})
        }
    }

    @Test fun folderDownloadSheet() = snap("folder_download_sheet") {
        val f = { id: String, name: String, size: Long -> dev.finalish.core.transfer.RemoteFile(id, name, size) }
        val tree = dev.finalish.core.transfer.FolderTree(
            name = "Brand film",
            files = listOf(f("1", "Script.pdf", 2_400_000), f("2", "Moodboard.png", 8_100_000)),
            stacks = listOf(dev.finalish.core.transfer.RemoteStack("Edit.mov", listOf(f("3", "Edit_v2.mov", 1_900_000_000), f("4", "Edit_v1.mov", 1_700_000_000)))),
            folders = listOf(dev.finalish.core.transfer.FolderTree("Stills", files = listOf(f("5", "A.jpg", 6_000_000), f("6", "B.jpg", 5_500_000)))),
        )
        androidx.compose.foundation.layout.Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(Palette.Surface2).padding(top = 20.dp)) {
            dev.finalish.ui.browser.FolderDownloadContent(dev.finalish.ui.browser.FolderDownloadUi.Ready("Brand film", tree), {}, {}, {})
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        androidx.compose.foundation.layout.Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(Palette.Surface2).padding(top = 20.dp)) {
            dev.finalish.ui.browser.FolderDownloadContent(dev.finalish.ui.browser.FolderDownloadUi.Scanning("Brand film", 37), {}, {}, {})
        }
    }
}
