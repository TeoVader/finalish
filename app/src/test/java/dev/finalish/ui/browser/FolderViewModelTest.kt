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
}
