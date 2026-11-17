package dev.finalish.ui.browser

import androidx.lifecycle.SavedStateHandle
import dev.finalish.core.data.SettingsStore
import dev.finalish.core.transfer.TransferManager
import dev.finalish.core.transfer.TransferStore
import dev.finalish.testing.FakeFrameio.Companion.page
import dev.finalish.testing.ViewModelTest
import dev.finalish.testing.awaitValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FolderViewModelTest : ViewModelTest() {
    private lateinit var vm: FolderViewModel
    private lateinit var saved: SavedStateHandle

    @Before fun setUp() {
        frameio.on(
            "GET accounts/acc/folders/f1/children",
            page(
                """{"id":"c1","type":"file","name":"clip.mov","media_type":"video/quicktime","created_at":"2026-01-02T00:00:00Z"}""",
                """{"id":"p1","type":"file","name":"photo.jpg","media_type":"image/jpeg","created_at":"2026-01-03T00:00:00Z"}""",
                """{"id":"d1","type":"folder","name":"Dailies"}""",
                """{"id":"n1","type":"file","name":"notes.pdf","media_type":"application/pdf","created_at":"2026-01-01T00:00:00Z"}""",
            ),
        )
        val settings = SettingsStore(app, testScope)
        val transfers = TransferStore(app.filesDir.resolve("folder-test").apply { deleteRecursively() })
        saved = SavedStateHandle()
        vm = FolderViewModel(app, saved, frameio.repository, settings, transfers, TransferManager(app, transfers, settings, testScope))
        vm.bind("acc", "f1")
        awaitValue(vm.items) { it.size == 4 }
    }

    @Test fun `folders come first`() {
        assertEquals("d1", vm.items.value.first().id)
    }

    @Test fun `the search field filters by name and survives process death`() {
        vm.setFilter("CLIP")
        assertEquals(listOf("c1"), awaitValue(vm.items) { it.size == 1 }.map { it.id })
        assertEquals("CLIP", saved.get<String>("filter"))
    }

    @Test fun `a type chip shows only that kind and toggles off when tapped again`() {
        vm.setKind(KindFilter.Images)
        assertEquals(listOf("p1"), awaitValue(vm.items) { it.size == 1 }.map { it.id })
        vm.setKind(KindFilter.Images)
        assertEquals(KindFilter.All, vm.kind.value)
        awaitValue(vm.items) { it.size == 4 }
    }

    @Test fun `select all takes what is visible, and selection toggles`() {
        vm.setKind(KindFilter.Documents)
        awaitValue(vm.items) { it.size == 1 }
        vm.selectAll()
        assertEquals(setOf("n1"), vm.selection.value)
        vm.toggleSelect("c1")
        assertEquals(setOf("n1", "c1"), vm.selection.value)
        vm.toggleSelect("n1")
        assertEquals(listOf("c1"), vm.selected().map { it.id })
        vm.clearSelection()
        assertTrue(vm.selection.value.isEmpty())
    }

    @Test fun `addToShare adds single item, clears selection and reports success`() {
        frameio.on("POST accounts/acc/shares/s1/assets", "{}")
        val share = dev.finalish.core.model.Share("s1", name = "Client Review")
        val asset = vm.items.value.first { it.id == "c1" }
        vm.toggleSelect("c1")
        assertEquals(setOf("c1"), vm.selection.value)
        val messages = record(vm.messages)

        vm.addToShare(share, listOf(asset))

        assertTrue(vm.selection.value.isEmpty())
        assertTrue("POST accounts/acc/shares/s1/assets" in frameio.calls)
        assertEquals(listOf(app.getString(dev.finalish.R.string.viewer_msg_added_to_share, "Client Review")), awaitValue(messages) { it.isNotEmpty() })
    }

    @Test fun `addToShare adds multiple items and reports count`() {
        frameio.on("POST accounts/acc/shares/s1/assets", "{}")
        val share = dev.finalish.core.model.Share("s1", name = "Client Review")
        val assets = vm.items.value.take(2)
        vm.toggleSelect(assets[0].id)
        vm.toggleSelect(assets[1].id)
        val messages = record(vm.messages)

        vm.addToShare(share, assets)

        assertTrue(vm.selection.value.isEmpty())
        assertEquals(listOf(app.getString(dev.finalish.R.string.shares_added_n_to_link, 2)), awaitValue(messages) { it.isNotEmpty() })
    }

    @Test fun `addToShare reports single item failure with error message`() {
        frameio.on("POST accounts/acc/shares/s1/assets", """{"errors":[{"detail":"Folders cannot be added to share"}]}""", code = 422)
        val share = dev.finalish.core.model.Share("s1", name = "Client Review")
        val folder = vm.items.value.first { it.id == "d1" }
        val messages = record(vm.messages)

        vm.addToShare(share, listOf(folder))

        assertTrue(vm.selection.value.isEmpty())
        assertEquals(listOf("Folders cannot be added to share"), awaitValue(messages) { it.isNotEmpty() })
    }

    @Test fun `addToShare reports failure count for multiple items`() {
        frameio.on("POST accounts/acc/shares/s1/assets", """{"errors":[{"detail":"Cannot add"}]}""", code = 422)
        val share = dev.finalish.core.model.Share("s1", name = "Client Review")
        val assets = vm.items.value.take(3)
        val messages = record(vm.messages)

        vm.addToShare(share, assets)

        assertTrue(vm.selection.value.isEmpty())
        assertEquals(listOf(app.resources.getQuantityString(dev.finalish.R.plurals.shares_couldnt_add_items, 3, 3)), awaitValue(messages) { it.isNotEmpty() })
    }

    private val selection = listOf(
        dev.finalish.core.model.Asset("c1", type = "file", name = "clip.mov", projectId = "pr"),
        dev.finalish.core.model.Asset("s1", type = "version_stack", name = "cut", projectId = "pr", headVersion = dev.finalish.core.model.Asset("h1", name = "cut v3")),
        dev.finalish.core.model.Asset("d1", type = "folder", name = "Dailies", projectId = "pr"),
    )
    private val status = dev.finalish.core.model.MetadataField(fieldType = "select", definitionId = "st", name = "Status", mutable = true)
    private val rating = dev.finalish.core.model.MetadataField(fieldType = "rating", definitionId = "rt", name = "Rating", mutable = true)
    private val metadataRoute = "PATCH accounts/acc/projects/pr/metadata/values"

    @Test fun `a status goes on the selected files and each stack's current version in one call, folders left out`() {
        frameio.on(metadataRoute, "{}")
        val messages = record(vm.messages)

        vm.setBulkStatus(selection, status, dev.finalish.core.model.SelectOption("approved", "Approved"))

        val sent = frameio.bodies.filter { it.first == metadataRoute }.map { it.second }
        assertEquals(1, sent.size)
        assertTrue(sent.single(), sent.single().contains("""file_ids":["c1","h1"]"""))
        assertTrue(sent.single(), sent.single().contains("""value":["approved"]"""))
        assertEquals(listOf("Status set on 2 files"), awaitValue(messages) { it.isNotEmpty() })
    }

    @Test fun `no status sends an empty list`() {
        frameio.on(metadataRoute, "{}")
        vm.setBulkStatus(selection.take(1), status, null)
        assertTrue(frameio.bodies.single { it.first == metadataRoute }.second.contains("""value":[]"""))
    }

    @Test fun `removing the rating clears it file by file`() {
        frameio.on(metadataRoute, "{}")
        vm.setBulkRating(selection, rating, 0)
        val sent = frameio.bodies.filter { it.first == metadataRoute }.map { it.second }
        assertEquals(2, sent.size)
        assertTrue(sent[0].contains("""file_ids":["c1"]""") && sent[1].contains("""file_ids":["h1"]"""))
    }

    @Test fun `another field goes on all files at once, and a cleared text field goes file by file`() {
        frameio.on(metadataRoute, "{}")
        val notes = dev.finalish.core.model.MetadataField(fieldType = "text", definitionId = "nt", name = "Notes", mutable = true)
        vm.toggleSelect("c1")

        vm.setBulkField(selection, notes, kotlinx.serialization.json.JsonPrimitive("Graded"))
        vm.setBulkField(selection, notes, kotlinx.serialization.json.JsonArray(emptyList()))

        val sent = frameio.bodies.filter { it.first == metadataRoute }.map { it.second }
        assertEquals(3, sent.size)
        assertTrue(sent[0], sent[0].contains("""file_ids":["c1","h1"]""") && sent[0].contains("""value":"Graded""""))
        assertTrue(sent[1].contains("""file_ids":["c1"]""") && sent[2].contains("""file_ids":["h1"]"""))
        // The fields sheet stays open on the same selection.
        assertEquals(setOf("c1"), vm.selection.value)
    }
}
