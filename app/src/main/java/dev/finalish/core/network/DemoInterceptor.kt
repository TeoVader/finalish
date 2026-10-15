package dev.finalish.core.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Serves a self-contained fake Frame.io account so the app can be explored
 * (and screenshotted) without credentials. Media points at public sample files.
 */
class DemoInterceptor(private val enabled: () -> Boolean) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!enabled() || req.url.host != "api.frame.io") return chain.proceed(req)
        val path = req.url.encodedPath.removePrefix("/v4/")
        val seg = path.split('/')
        val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
        // One request at a time: the demo edits below live in plain mutable collections.
        val (code, json) = synchronized(this) { route(req.method, seg, body) }
        Thread.sleep(180) // feel like a network
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body((json?.toString() ?: "").toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun route(method: String, s: List<String>, body: String?): Pair<Int, JsonElement?> {
        fun page(items: List<JsonElement>) = 200 to buildJsonObject {
            put("data", JsonArray(items)); putJsonObject("links") { put("next", JsonPrimitive(null as String?)) }
        }
        fun data(e: JsonElement) = 200 to buildJsonObject { put("data", e) }
        return when {
            s == listOf("me") -> data(ME)
            s == listOf("accounts") -> page(listOf(ACCOUNT))
            s.size == 3 && s[2] == "workspaces" -> page(WORKSPACES)
            s.size == 5 && s[4] == "projects" -> page(PROJECTS.filter { it.jsonObject["workspace_id"]?.toString()?.trim('"') == s[3] })
            s.size == 4 && s[2] == "projects" -> data(PROJECTS.first { it.jsonObject["id"].str() == s[3] })
            s.size == 5 && s[2] == "folders" && s[4] == "children" -> page(children(s[3]))
            s.size == 4 && s[2] == "folders" && method == "GET" -> data(folderJson(s[3], "Folder", ""))
            s.size == 5 && s[2] == "folders" && s[4] == "folders" -> 201 to buildJsonObject {
                put("data", folderJson(UUID.randomUUID().toString(), "New folder", s[3]))
            }
            s.size == 4 && s[2] == "files" && method == "GET" -> data(FILES.first { it.jsonObject["id"].str() == s[3] })
            // Like Frame.io: the head (newest) version first.
            s.size == 5 && s[2] == "version_stacks" && s[4] == "children" -> page(FILES.filter { it.jsonObject["parent_id"].str() == s[3] }.reversed())
            s.size == 5 && s[2] == "files" && s[4] == "comments" && method == "GET" -> page(COMMENTS[s[3]].orEmpty() + posted[s[3]].orEmpty())
            s.size == 5 && s[2] == "files" && s[4] == "comments" && method == "POST" -> {
                val d = body?.let { AppJson.parseToJsonElement(it).jsonObject["data"]?.jsonObject } ?: JsonObject(emptyMap())
                val comment = JsonObject(d + mapOf(
                    "id" to JsonPrimitive(UUID.randomUUID().toString()),
                    "file_id" to JsonPrimitive(s[3]),
                    "created_at" to JsonPrimitive(Instant.now().toString()),
                    "owner" to ME,
                ))
                // Kept for the session, so a refresh still shows it.
                posted.getOrPut(s[3]) { mutableListOf() }.add(comment)
                201 to buildJsonObject { put("data", comment) }
            }
            s.size == 4 && s[2] == "comments" && method == "PATCH" -> {
                val d = body?.let { AppJson.parseToJsonElement(it).jsonObject["data"]?.jsonObject } ?: JsonObject(emptyMap())
                data(buildJsonObject {
                    put("id", s[3]); put("text", d["text"] ?: JsonPrimitive(""))
                    if (d["completed"]?.toString() == "true") put("completed_at", Instant.now().toString())
                })
            }
            s.size == 5 && s[2] == "projects" && s[4] == "shares" && method == "GET" -> page(SHARES)
            s.size == 5 && s[2] == "projects" && s[4] == "shares" -> 201 to buildJsonObject {
                putJsonObject("data") {
                    put("id", UUID.randomUUID().toString()); put("name", "Demo share"); put("access", "public")
                    put("enabled", true); put("short_url", "https://f.io/demo123")
                }
            }
            s.size == 5 && s[2] == "projects" && s[4] == "collections" -> page(listOf(buildJsonObject {
                put("id", "col1"); put("name", "Selects"); put("project_id", s[3]); put("root_folder_id", "f-dailies")
                put("aggregation_mode", "static"); put("updated_at", ago(3)); put("private", false)
            }))
            s.size == 3 && s[2] == "search" -> page(FILES.take(4).map { f -> buildJsonObject { put("type", "file_result"); put("result", f) } })
            // People, groups, metadata fields, watermark templates, imports.
            s.size == 3 && s[2] == "users" -> page(PEOPLE.map { (u, role) -> buildJsonObject { put("role", role); put("user", u) } })
            s.size == 5 && s[4] == "users" && s[2] in setOf("workspaces", "projects", "folders") && method == "GET" ->
                page(PEOPLE.take(if (s[2] == "folders") 2 else 4).map { (u, role) -> buildJsonObject { put("role", role); put("user", u) } })
            s.size == 3 && s[2] == "groups" && method == "GET" -> page(groups.sortedBy { it.jsonObject["name"].str().orEmpty().lowercase() })
            s.size == 3 && s[2] == "groups" && method == "POST" -> {
                val g = group(UUID.randomUUID().toString(), dataOf(body)["name"].str() ?: "Group", dataOf(body)["emoji"].str() ?: "👥", 0)
                groups += g; groupPeople[g.jsonObject["id"].str()!!] = mutableListOf()
                201 to buildJsonObject { put("data", g) }
            }
            s.size == 4 && s[2] == "groups" && method == "PATCH" -> {
                val i = groups.indexOfFirst { it.jsonObject["id"].str() == s[3] }
                if (i < 0) 404 to null else {
                    groups[i] = JsonObject(groups[i].jsonObject + dataOf(body)); data(groups[i])
                }
            }
            s.size == 4 && s[2] == "groups" && method == "DELETE" -> { groups.removeAll { it.jsonObject["id"].str() == s[3] }; 204 to null }
            s.size == 5 && s[2] == "groups" && s[4] == "users" && method == "GET" -> page(groupPeople[s[3]].orEmpty().map { PEOPLE[it].first })
            s.size == 5 && s[2] == "groups" && s[4] == "users" && method == "POST" -> {
                val ids = (dataOf(body)["user_ids"] as? JsonArray).orEmpty().mapNotNull { it.str() }
                val list = groupPeople.getOrPut(s[3]) { mutableListOf() }
                ids.forEach { id -> PEOPLE.indexOfFirst { it.first["id"].str() == id }.takeIf { it >= 0 && it !in list }?.let(list::add) }
                recount(s[3]); 204 to null
            }
            s.size == 6 && s[2] == "groups" && s[4] == "users" && method == "DELETE" -> {
                groupPeople[s[3]]?.removeAll { PEOPLE[it].first["id"].str() == s[5] }; recount(s[3]); 204 to null
            }
            s.size == 5 && s[4] == "groups" && s[2] in setOf("projects", "workspaces") ->
                page(groups.take(2).zip(listOf("editor", "commenter")).map { (g, r) -> buildJsonObject { put("group", g); put("role", r) } })
            s.size == 4 && s[2] == "metadata" && s[3] == "field_definitions" && method == "GET" -> page(fields)
            s.size == 4 && s[2] == "metadata" && s[3] == "field_definitions" && method == "POST" -> {
                val f = JsonObject(dataOf(body) + mapOf("id" to JsonPrimitive(UUID.randomUUID().toString()), "mutable" to JsonPrimitive(true)))
                fields += f; 201 to buildJsonObject { put("data", f) }
            }
            s.size == 5 && s[2] == "metadata" && s[3] == "field_definitions" && method == "PATCH" -> {
                val i = fields.indexOfFirst { it.jsonObject["id"].str() == s[4] }
                if (i < 0) 404 to null else { fields[i] = JsonObject(fields[i].jsonObject + dataOf(body)); data(fields[i]) }
            }
            s.size == 5 && s[2] == "metadata" && s[3] == "field_definitions" && method == "DELETE" -> {
                fields.removeAll { it.jsonObject["id"].str() == s[4] }; 204 to null
            }
            s.size == 3 && s[2] == "session_watermark_templates" -> page(WATERMARKS)
            s.size == 5 && s[2] == "files" && s[4] == "status" -> data(buildJsonObject {
                put("id", s[3]); put("upload_complete", true); put("upload_failed", false)
            })
            s.size == 4 && s[2] == "shares" && method == "GET" ->
                SHARES.firstOrNull { it.jsonObject["id"].str() == s[3] }?.let { data(it) } ?: (404 to null)
            // What each demo link shares: a few files, so the editor and cards show their previews.
            s.size == 5 && s[2] == "shares" && s[4] == "assets" && method == "GET" ->
                page(FILES.filter { it.jsonObject["type"].str() == "file" && it.jsonObject["media_type"].str() == "video/mp4" }.let { if (s[3] == "sh1") it.take(1) else it.take(4) })
            s.size == 4 && s[2] == "shares" && method == "PATCH" -> {
                val base = SHARES.firstOrNull { it.jsonObject["id"].str() == s[3] }?.jsonObject ?: buildJsonObject { put("id", s[3]) }
                data(JsonObject(base + dataOf(body)))
            }
            method == "DELETE" -> 204 to null
            // Other edits: echo what was sent, so callers that read the result back get it.
            method == "PATCH" -> data(JsonObject(mapOf("id" to JsonPrimitive(s.last())) + dataOf(body)))
            else -> 404 to buildJsonObject { putJsonArray("errors") { add(buildJsonObject { put("detail", "Not available in demo") }) } }
        }
    }

    private fun JsonElement?.str() = (this as? JsonPrimitive)?.content

    private fun dataOf(body: String?): JsonObject =
        body?.let { runCatching { AppJson.parseToJsonElement(it).jsonObject["data"]?.jsonObject }.getOrNull() } ?: JsonObject(emptyMap())

    // Demo edits to groups and fields last until the app restarts.
    private val groups = mutableListOf(
        group("g1", "Editors", "✂️", 3), group("g2", "Client", "🤝", 2), group("g3", "Sound team", "🎧", 2, source = "adobe"),
    )
    private val groupPeople = mutableMapOf("g1" to mutableListOf(0, 1, 2), "g2" to mutableListOf(3, 4), "g3" to mutableListOf(1, 5))
    private val fields = FIELDS.toMutableList()
    private val posted = mutableMapOf<String, MutableList<JsonElement>>()

    private fun recount(id: String) {
        val i = groups.indexOfFirst { it.jsonObject["id"].str() == id }
        if (i >= 0) groups[i] = JsonObject(groups[i].jsonObject + ("member_count" to JsonPrimitive(groupPeople[id].orEmpty().size)))
    }

    private fun children(folder: String): List<JsonElement> = when (folder) {
        "root-campaign" -> listOf(
            folderJson("f-dailies", "Dailies", folder),
            folderJson("f-gfx", "Graphics & Stills", folder),
            stackJson(),
        ) + FILES.filter { it.jsonObject["parent_id"].str() == folder }
        else -> FILES.filter { it.jsonObject["parent_id"].str() == folder }
    }

    private companion object {
        const val TV = "https://test-videos.co.uk/vids/"
        val CLIPS = mapOf(
            "bunny" to "${TV}bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4",
            "jelly" to "${TV}jellyfish/mp4/h264/720/Jellyfish_720_10s_1MB.mp4",
            "sintel" to "${TV}sintel/mp4/h264/720/Sintel_720_10s_1MB.mp4",
            "sintelTrailer" to "https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4",
            "tos" to "https://download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov",
        )
        fun ago(days: Long) = Instant.now().minus(days, ChronoUnit.DAYS).toString()
        fun hours(h: Long) = Instant.now().minus(h, ChronoUnit.HOURS).toString()

        val ME = buildJsonObject {
            put("id", "u-me"); put("name", "Noa Lindqvist"); put("email", "noa@example.com"); put("active", true)
        }
        val ACCOUNT = buildJsonObject {
            put("id", "acc-demo"); put("display_name", "Mosswood Pictures")
            putJsonArray("roles") { add(JsonPrimitive("owner")) }
            put("storage_usage", 412_000_000_000); put("storage_limit", 2_000_000_000_000)
        }
        val WORKSPACES = listOf(
            buildJsonObject { put("id", "ws1"); put("account_id", "acc-demo"); put("name", "Commercial") },
            buildJsonObject { put("id", "ws2"); put("account_id", "acc-demo"); put("name", "Documentary") },
        )
        fun project(id: String, ws: String, name: String, root: String, days: Long, storage: Long) = buildJsonObject {
            put("id", id); put("workspace_id", ws); put("name", name); put("root_folder_id", root)
            put("status", "active"); put("storage", storage); put("updated_at", ago(days)); put("description", JsonPrimitive(null as String?))
        }
        val PROJECTS = listOf(
            project("p1", "ws1", "Saltmarsh — Spring Campaign", "root-campaign", 0, 84_000_000_000),
            // Each project has its own root, so covers and "recently opened" name the right project.
            project("p2", "ws1", "Velo Bikes Launch", "root-p2", 2, 23_000_000_000),
            project("p3", "ws1", "Brand Refresh 2026", "root-p3", 9, 5_400_000_000),
            project("p4", "ws2", "The Long Coast", "root-p4", 1, 210_000_000_000),
            project("p5", "ws2", "Interviews — Ep. 3", "root-p5", 15, 31_000_000_000),
        )

        fun folderJson(id: String, name: String, parent: String) = buildJsonObject {
            put("id", id); put("type", "folder"); put("name", name); put("parent_id", parent)
            put("project_id", "p1"); put("restricted", false); put("updated_at", hours(5)); put("view_url", "https://next.frame.io")
        }

        val STATUS_OPTIONS = listOf("In Progress" to "s1", "Needs Review" to "s2", "Approved" to "s3")
        fun meta(status: Int?, rating: Int?, duration: Double?) = buildJsonArray {
            add(buildJsonObject {
                put("field_type", "select"); put("field_definition_id", "fd-status"); put("field_definition_name", "Status"); put("mutable", true)
                putJsonArray("value") { status?.let { i -> add(buildJsonObject { put("id", STATUS_OPTIONS[i].second); put("display_name", STATUS_OPTIONS[i].first) }) } }
                putJsonArray("field_options") { STATUS_OPTIONS.forEach { (n, id) -> add(buildJsonObject { put("id", id); put("display_name", n) }) } }
            })
            add(buildJsonObject {
                put("field_type", "rating"); put("field_definition_id", "fd-rating"); put("field_definition_name", "Rating"); put("mutable", true)
                put("value", rating?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as Int?))
            })
            duration?.let {
                add(buildJsonObject {
                    put("field_type", "number"); put("field_definition_id", "fd-dur"); put("field_definition_name", "Duration"); put("mutable", false); put("value", it)
                })
            }
            add(buildJsonObject {
                put("field_type", "text"); put("field_definition_id", "fd-notes"); put("field_definition_name", "Notes"); put("mutable", true); put("value", "Client cut — keep music bed")
            })
            add(buildJsonObject {
                put("field_type", "number"); put("field_definition_id", "fd-fps"); put("field_definition_name", "Frame Rate"); put("mutable", false); put("value", 24.0)
            })
        }

        fun video(id: String, name: String, file: String, parent: String, status: Int?, rating: Int?, dur: Double, h: Long, project: String = "p1") = buildJsonObject {
            put("id", id); put("type", "file"); put("name", name); put("parent_id", parent); put("project_id", project)
            put("media_type", "video/mp4"); put("status", "transcoded"); put("file_size", (dur * 1_900_000).toLong())
            put("created_at", hours(h + 30)); put("updated_at", hours(h)); put("view_url", "https://next.frame.io/project/$project/view/$id")
            put("creator", buildJsonObject { put("id", "u2"); put("name", "Kai Brennan"); put("email", "kai@example.com") })
            val url = CLIPS.getValue(file)
            val thumb = "https://picsum.photos/seed/$id$file/960/540"
            putJsonObject("media_links") {
                putJsonObject("thumbnail") { put("url", thumb) }
                putJsonObject("thumbnail_high_quality") { put("url", thumb) }
                putJsonObject("high_quality") { put("url", url); put("download_url", url) }
                putJsonObject("efficient") { put("url", url); put("download_url", url) }
                putJsonObject("original") { put("inline_url", url); put("download_url", url) }
            }
            put("metadata", meta(status, rating, dur))
        }

        fun image(id: String, name: String, pic: Int, parent: String, status: Int?, h: Long, project: String = "p1") = buildJsonObject {
            put("id", id); put("type", "file"); put("name", name); put("parent_id", parent); put("project_id", project)
            put("media_type", "image/jpeg"); put("status", "transcoded"); put("file_size", 6_400_000)
            put("created_at", hours(h + 2)); put("updated_at", hours(h)); put("view_url", "https://next.frame.io/project/$project/view/$id")
            put("creator", ME)
            putJsonObject("media_links") {
                putJsonObject("thumbnail") { put("url", "https://picsum.photos/id/$pic/640/400") }
                putJsonObject("thumbnail_high_quality") { put("url", "https://picsum.photos/id/$pic/960/600") }
                putJsonObject("high_quality") { put("url", "https://picsum.photos/id/$pic/2400/1500") }
                putJsonObject("original") { put("inline_url", "https://picsum.photos/id/$pic/2400/1500"); put("download_url", "https://picsum.photos/id/$pic/2400/1500") }
            }
            put("metadata", meta(status, null, null))
        }

        val FILES: List<JsonElement> = listOf(
            video("v1", "Saltmarsh_Hero_30s_v4.mp4", "tos", "root-campaign", 1, 4, 734.0, 2),
            video("v2", "Saltmarsh_Cutdown_15s.mp4", "bunny", "root-campaign", 2, 3, 10.0, 6),
            video("v3", "BTS_Montage.mp4", "jelly", "root-campaign", 0, null, 10.0, 30),
            image("i1", "Key_Visual_Final.jpg", 1015, "root-campaign", 2, 20),
            video("d1", "A001_C004_0412.mov", "bunny", "f-dailies", null, null, 10.0, 48),
            video("d2", "A001_C007_0412.mov", "jelly", "f-dailies", null, 5, 10.0, 48),
            video("d3", "B002_C001_0413.mov", "sintel", "f-dailies", 0, null, 10.0, 26),
            image("g1", "Storyboard_01.jpg", 1039, "f-gfx", null, 70),
            image("g2", "Storyboard_02.jpg", 1043, "f-gfx", null, 70),
            image("g3", "Lookbook_Coast.jpg", 1018, "f-gfx", 2, 40),
            video("vs1", "Sintel_Trailer_v1.mp4", "sintel", "stack1", 0, null, 10.0, 200),
            video("vs2", "Sintel_Trailer_v2.mp4", "sintelTrailer", "stack1", 1, null, 52.0, 90),
            video("vs3", "Sintel_Trailer_v3.mp4", "sintelTrailer", "stack1", 2, 4, 52.0, 4),
            video("b1", "Velo_Teaser_v2.mp4", "bunny", "root-p2", 1, 4, 10.0, 50, project = "p2"),
            image("b2", "Velo_Product_Shot.jpg", 1016, "root-p2", 2, 60, project = "p2"),
            image("r1", "Logo_Exploration.jpg", 1019, "root-p3", 0, 210, project = "p3"),
            image("r2", "Palette_Board.jpg", 1036, "root-p3", null, 216, project = "p3"),
            video("c1", "Coast_Rough_Cut_v1.mov", "tos", "root-p4", 0, null, 734.0, 25, project = "p4"),
            image("c2", "Location_Scout_North.jpg", 1040, "root-p4", null, 40, project = "p4"),
            video("n1", "Interview_Ep3_CamA.mov", "jelly", "root-p5", null, null, 10.0, 360, project = "p5"),
            video("n2", "Interview_Ep3_CamB.mov", "sintel", "root-p5", null, 3, 10.0, 362, project = "p5"),
        )

        fun stackJson() = buildJsonObject {
            put("id", "stack1"); put("type", "version_stack"); put("name", "Sintel_Trailer"); put("parent_id", "root-campaign")
            put("project_id", "p1"); put("updated_at", hours(4)); put("view_url", "https://next.frame.io")
            put("head_version", FILES.last { (it.jsonObject["parent_id"] as? JsonPrimitive)?.content == "stack1" })
        }

        fun comment(id: String, text: String, frame: Int?, name: String, h: Long, done: Boolean = false, annotation: String? = null, dur: Int? = null) =
            buildJsonObject {
                put("id", id); put("text", text); frame?.let { put("timestamp", it) }; dur?.let { put("duration", it) }
                put("created_at", hours(h)); annotation?.let { put("annotation", it) }
                if (done) put("completed_at", hours(1))
                put("owner", buildJsonObject { put("id", "u-$name"); put("name", name); put("email", "$name@example.com") })
                putJsonArray("replies") {}
                putJsonArray("links") {}
            }

        val ARROW = """[{"tool":"arrow","color":"#FFB800","size":8,"x":0.32,"y":0.62,"w":0.18,"h":-0.2}]"""
        val RECT = """[{"tool":"rect","color":"#F22237","size":8,"x":0.55,"y":0.2,"w":0.3,"h":0.42}]"""

        val COMMENTS: Map<String, List<JsonElement>> = mapOf(
            "v1" to listOf(
                comment("c1", "Love this opening — can we hold the wide 6 more frames?", 96, "Tomás Okafor", 20),
                comment("c2", "Logo lockup feels a bit small here. Bump 10%?", 312, "Ines Varga", 18, annotation = RECT),
                comment("c3", "Color: lift the shadows slightly, too crushed.", 1450, "Kai Brennan", 9, annotation = ARROW),
                comment("c4", "Music swell lands perfectly 🔥", 2400, "Tomás Okafor", 5, done = true),
                comment("c5", "Typo in the super: “Saltmrash” → “Saltmarsh”", 5210, "Ines Varga", 2, dur = 48),
                comment("c6", "Final frame approved by client.", null, "Noa Lindqvist", 1),
            ),
            "vs3" to listOf(comment("c7", "V3 fixes the sky replacement, nice.", 240, "Kai Brennan", 3)),
            "i1" to listOf(comment("c8", "Crop tighter on the right?", null, "Tomás Okafor", 6, annotation = RECT)),
        )

        fun person(id: String, name: String) = buildJsonObject {
            put("id", id); put("name", name); put("email", name.substringBefore(' ').lowercase() + "@example.com"); put("active", true)
        }
        val PEOPLE: List<Pair<JsonObject, String>> = listOf(
            ME to "owner",
            person("u2", "Kai Brennan") to "full_access",
            person("u-Tomás Okafor", "Tomás Okafor") to "editor",
            person("u-Ines Varga", "Ines Varga") to "commenter",
            person("u5", "Marta Conti") to "viewer",
            person("u6", "Leo Brandt") to "editor",
        )

        fun group(id: String, name: String, emoji: String, count: Int, source: String = "frame") = buildJsonObject {
            put("id", id); put("name", name); put("emoji", emoji); put("member_count", count); put("source", source)
        }

        fun field(id: String, name: String, type: String, mutable: Boolean = true, config: JsonObject? = null) = buildJsonObject {
            put("id", id); put("name", name); put("field_type", type); put("mutable", mutable)
            config?.let { put("field_configuration", it) }
        }
        fun options(vararg o: String) = buildJsonObject {
            put("enable_add_new", true)
            putJsonArray("options") { o.forEachIndexed { i, n -> add(buildJsonObject { put("id", "opt$i-$n"); put("display_name", n) }) } }
        }
        val FIELDS: List<JsonElement> = listOf(
            field("cf1", "Scene", "select", config = options("Exterior", "Interior", "Day", "Night")),
            field("cf2", "Take", "number", config = buildJsonObject { put("scale", 0) }),
            field("cf3", "Deliverables", "select_multi", config = options("Web", "TV", "Social")),
            field("cf4", "Approved by", "user_single"),
            field("fd-status", "Status", "select", mutable = false, config = options(*STATUS_OPTIONS.map { it.first }.toTypedArray())),
            field("fd-rating", "Rating", "rating", mutable = false, config = buildJsonObject { put("max_value", 5); put("style", "star") }),
            field("fd-notes", "Notes", "long_text", mutable = false),
        )

        fun block(position: String, vararg points: Pair<String, String?>) = buildJsonObject {
            put("position", position)
            putJsonArray("data_points") {
                points.forEachIndexed { i, (type, value) -> add(buildJsonObject { put("type", type); value?.let { put("value", it) }; put("order", i + 1) }) }
            }
        }
        val WATERMARKS = listOf(
            buildJsonObject {
                put("id", "wm1"); put("name", "Client review")
                putJsonArray("watermark_blocks") { add(block("bottom_right", "email" to null, "timestamp" to null)) }
            },
            buildJsonObject {
                put("id", "wm2"); put("name", "Confidential")
                putJsonArray("watermark_blocks") { add(block("middle_center", "custom_text" to "CONFIDENTIAL")) }
            },
        )

        val SHARES = listOf(
            buildJsonObject {
                put("id", "sh1"); put("name", "Client review — Hero v4"); put("access", "public"); put("enabled", true)
                put("short_url", "https://f.io/aBc123"); put("last_viewed_at", hours(3)); put("downloading_enabled", false); put("commenting_enabled", true)
            },
            buildJsonObject {
                put("id", "sh2"); put("name", "Agency selects"); put("access", "secure"); put("enabled", false)
                put("short_url", "https://f.io/xYz789"); put("downloading_enabled", true); put("commenting_enabled", true)
            },
        )
    }
}
