package dev.finalish.core.data

import dev.finalish.core.model.Asset
import dev.finalish.core.model.FolderPreview
import dev.finalish.core.model.Account
import dev.finalish.core.model.ImageRef
import dev.finalish.core.model.Project
import dev.finalish.core.model.User
import dev.finalish.core.model.Workspace
import dev.finalish.core.model.linkImageKey
import dev.finalish.core.network.AppJson
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Signed links stay in memory: what JsonCache writes to disk, and what reads back from it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DiskScrubTest {
    @get:Rule val tmp = TemporaryFolder()

    private val signed = "https://assets.example/t.jpg?Expires=4102444800&Signature=abc"

    private val stack = """
        {"id":"vs1","type":"version_stack","name":"cut.mov","creator":{"name":"Ada"},
         "head_version":{"id":"v2","type":"file","name":"cut.mov",
           "media_links":{"thumbnail":{"url":"$signed"},"original":{"download_url":"$signed","inline_url":"$signed"},
             "scrub_sheet":{"url":"$signed","metadata":{"tile_x":10,"tile_y":5}},"efficient":{"url":null}}}}
    """.trimIndent()

    @Test fun `media links lose their urls and say which they had`() {
        val clean = DiskScrub.scrub(AppJson.parseToJsonElement(stack))
        assertFalse(clean.toString().contains("Signature"))
        val links = clean.jsonObject["head_version"]!!.jsonObject["media_links"]!!.jsonObject
        assertEquals(listOf("download_url", "inline_url"), links["original"]!!.jsonObject["stripped"]!!.jsonArray.map { it.jsonPrimitive.content })
        // What isn't a link stays: the scrub sheet's tiles, a link Frame.io sent empty, the rest of the asset.
        assertEquals(10, links["scrub_sheet"]!!.jsonObject["metadata"]!!.jsonObject["tile_x"]!!.jsonPrimitive.content.toInt())
        assertEquals(JsonObject(mapOf("url" to kotlinx.serialization.json.JsonNull)), links["efficient"])
        assertEquals("Ada", clean.jsonObject["creator"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun `scrubbing twice changes nothing more`() {
        val once = DiskScrub.scrub(AppJson.parseToJsonElement(stack))
        assertEquals(once, DiskScrub.scrub(once))
    }

    @Test fun `a scrubbed asset still knows its media`() {
        val a = AppJson.decodeFromString(Asset.serializer(), DiskScrub.scrub(AppJson.parseToJsonElement(stack)).toString())
        assertNull(a.thumbnailUrl)
        assertEquals(ImageRef("frameio:v2:thumbnail"), a.thumbnail)
        assertTrue(a.media.mediaLinks!!.original!!.hasDownload)
        assertTrue(a.media.mediaLinks!!.scrubSheet!!.hasStream)
        assertFalse(a.media.mediaLinks!!.efficient!!.hasStream)
        assertTrue(a.media.mediaLinks!!.withoutUrls)
    }

    @Test fun `image refs keep their key and drop their link`() {
        val preview = FolderPreview(listOf(ImageRef("frameio:f1:thumbnail", signed)), 3)
        val clean = DiskScrub.scrub(AppJson.encodeToJsonElement(FolderPreview.serializer(), preview)).toString()
        assertEquals(FolderPreview(listOf(ImageRef("frameio:f1:thumbnail")), 3), AppJson.decodeFromString(FolderPreview.serializer(), clean))
    }

    @Test fun `the cache keeps links in memory and none on disk`() = runBlocking {
        val cache = JsonCache(tmp.root)
        val asset = AppJson.decodeFromString(Asset.serializer(), stack)
        cache.put("file:a:vs1", Asset.serializer(), asset)
        assertEquals(asset, cache.peek<Asset>("file:a:vs1"))
        val onDisk = File(tmp.root, "api").listFiles()!!.single().readText()
        assertFalse(onDisk.contains("Signature"))
        // A new process reads the disk copy.
        val read = JsonCache(tmp.root).get("file:a:vs1", Asset.serializer())!!
        assertNull(read.thumbnailUrl)
        assertEquals(asset.thumbnail?.key, read.thumbnail?.key)
    }

    @Test fun `legacy entries are scrubbed once and old previews dropped`() = runBlocking {
        val dir = File(tmp.root, "api").apply { mkdirs() }
        val file = File(dir, "a.json").apply { writeText(stack) }
        val preview = File(dir, "b.json").apply { writeText("""{"thumbnails":["$signed"],"count":2}""") }
        val count = File(dir, "c.json").apply { writeText("7") }
        val old = 1_000_000_000_000L
        file.setLastModified(old)
        JsonCache(tmp.root).scrubLegacyFiles()
        assertFalse(file.readText().contains("Signature"))
        assertEquals("keeps its age for the trim", old, file.lastModified())
        assertFalse(preview.exists())
        assertEquals("7", count.readText())
        // Done once: a file written afterwards is not touched again.
        file.writeText(stack)
        JsonCache(tmp.root).scrubLegacyFiles()
        assertTrue(file.readText().contains("Signature"))
    }

    private val s3 = "https://frameio-uploads.s3.amazonaws.com/p/cover.jpg?X-Amz-Expires=3600&X-Amz-Signature=abc"

    /** The Home list as Frame.io sends it: every image a signed link. */
    private val home = listOf(
        WorkspaceProjects(
            Workspace("w1", "acc", "Studio", image = signed),
            listOf(
                Project("p1", "Brand film", image = s3, owner = User("u1", "Ada", avatarUrl = signed)),
                Project("p2", "No cover"),
            ),
        ),
    )

    private fun String.hasSignedLink() = contains("Signature") || contains("Expires=")

    @Test fun `the home list keeps no signed image link on disk`() = runBlocking {
        val ser = ListSerializer(WorkspaceProjects.serializer())
        JsonCache(tmp.root).put("ws:acc", ser, home)
        val onDisk = File(tmp.root, "api").listFiles()!!.single().readText()
        assertFalse(onDisk, onDisk.hasSignedLink())
        assertFalse(onDisk, onDisk.contains("cover.jpg"))

        // A new process (offline Home): no links, but the keys the image cache knows them by.
        val read = JsonCache(tmp.root).get("ws:acc", ser)!!.single()
        val p1 = read.projects[0]
        assertNull(p1.image)
        assertEquals(ImageRef(linkImageKey(s3)), p1.imageRef)
        assertEquals(home[0].projects[0].imageRef!!.key, p1.imageRef!!.key)
        assertNull(p1.owner!!.avatarUrl)
        assertEquals(ImageRef(linkImageKey(signed)), p1.owner!!.avatar)
        assertNull(read.workspace.image)
        assertEquals(linkImageKey(signed), read.workspace.imageKey)
        // A project without an image stays without one: its card shows the latest file or the gradient.
        assertNull(read.projects[1].image)
        assertNull(read.projects[1].imageRef)
        assertEquals("Brand film", p1.name)
    }

    @Test fun `accounts and authors keep no signed image link on disk`() = runBlocking {
        val accounts = ListSerializer(Account.serializer())
        val authors = kotlinx.serialization.builtins.MapSerializer(String.serializer(), User.serializer())
        JsonCache(tmp.root).put("accounts", accounts, listOf(Account("acc", "Studio", image = signed)))
        JsonCache(tmp.root).put("authors:acc", authors, mapOf("c1" to User(name = "Ann", avatarUrl = s3)))
        File(tmp.root, "api").listFiles()!!.forEach { assertFalse(it.readText(), it.readText().hasSignedLink()) }

        val account = JsonCache(tmp.root).get("accounts", accounts)!!.single()
        assertNull(account.image)
        assertEquals(ImageRef(linkImageKey(signed)), account.imageRef)
        val ann = JsonCache(tmp.root).get("authors:acc", authors)!!.getValue("c1")
        assertNull(ann.avatarUrl)
        assertEquals(ImageRef(linkImageKey(s3)), ann.avatar)
    }

    @Test fun `an image key is stable across signatures and holds no link`() {
        val other = s3.replace("abc", "def")
        assertEquals(linkImageKey(s3), linkImageKey(other))
        assertFalse(linkImageKey(s3).contains("amazonaws"))
        // A refresh brings the link back; the key stays the same, so the cached picture shows meanwhile.
        assertEquals(ImageRef(linkImageKey(s3), other), Project("p1", image = other, imageKey = linkImageKey(s3)).imageRef)
    }

    @Test fun `image fields scrub once`() {
        val once = DiskScrub.scrub(AppJson.encodeToJsonElement(ListSerializer(WorkspaceProjects.serializer()), home))
        assertEquals(once, DiskScrub.scrub(once))
    }

    @Test fun `legacy entries with signed images are scrubbed by this version`() = runBlocking {
        // Written by the version that scrubbed media links only, which left its marker.
        File(tmp.root, "api.links-scrubbed").createNewFile()
        val dir = File(tmp.root, "api").apply { mkdirs() }
        val file = File(dir, "ws.json").apply { writeText(AppJson.encodeToString(ListSerializer(WorkspaceProjects.serializer()), home)) }
        JsonCache(tmp.root).scrubLegacyFiles()
        assertFalse(file.readText().hasSignedLink())
    }
}
