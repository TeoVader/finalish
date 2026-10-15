package dev.finalish.ui.viewer

import dev.finalish.core.model.Asset
import dev.finalish.core.model.MetadataField
import dev.finalish.core.model.SelectOption
import dev.finalish.core.model.User
import dev.finalish.core.network.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The file's metadata fields in the viewer: the full field list, edits, and people to pick from. */
internal class ViewerMetadata(private val session: ViewerSession) {
    private val state = session.state
    private val messages = session.messages
    private val scope = session.scope
    private val repo = session.repo
    private val account get() = session.account

    /** Fields from `?show_null=true`, so empty Status/Rating/custom fields can be set. */
    private var fullMeta: List<dev.finalish.core.model.MetadataField>? = null

    /** [f] with the empty fields filled in from the full list, once it has loaded. */
    fun withMeta(f: Asset): Asset {
        val full = fullMeta ?: return f
        val present = f.metadata.associateBy { it.definitionId }
        // Keep fresher values from the file payload; fill gaps from the full list.
        return f.copy(metadata = full.map { present[it.definitionId] ?: it } + f.metadata.filter { m -> full.none { it.definitionId == m.definitionId } })
    }

    private var metaJob: Job? = null

    /** Loads [fileId]'s full field list (the previous file's is dropped at once). */
    fun load(fileId: String) {
        fullMeta = null
        metaJob?.cancel()
        metaJob = scope.launch {
            runCatching { repo.fullMetadata(account, fileId) }.onSuccess { meta ->
                // A slower answer for the previous version must not fill this one's fields.
                if (state.value.file?.id != fileId) return@onSuccess
                fullMeta = meta
                state.update { s -> s.copy(file = s.file?.takeIf { it.id == fileId }?.let { withMeta(it) }) }
            }
        }
    }

    fun setSelect(field: MetadataField, option: SelectOption?) {
        val value: JsonElement = JsonArray(listOfNotNull(option?.let { JsonPrimitive(it.id) }))
        val local = JsonArray(listOfNotNull(option?.let {
            buildJsonObject { put("id", it.id); put("display_name", it.displayName) }
        }))
        updateField(field, value, local)
    }

    fun setRating(field: MetadataField, rating: Int) =
        updateField(field, if (rating == 0) CLEAR else JsonPrimitive(rating), if (rating == 0) JsonNull else JsonPrimitive(rating))

    fun setText(field: MetadataField, text: String) =
        updateField(field, JsonPrimitive(text), JsonPrimitive(text))

    fun setNumber(field: MetadataField, n: Double?) =
        updateField(field, n?.let { JsonPrimitive(it) } ?: CLEAR, n?.let { JsonPrimitive(it) } ?: JsonNull)

    fun setToggle(field: MetadataField, b: Boolean) = updateField(field, JsonPrimitive(b), JsonPrimitive(b))

    fun setMultiSelect(field: MetadataField, options: List<SelectOption>) = updateField(
        field,
        JsonArray(options.map { JsonPrimitive(it.id) }),
        JsonArray(options.map { o -> buildJsonObject { put("id", o.id); put("display_name", o.displayName) } }),
    )

    fun setUsers(field: MetadataField, ids: List<String>) {
        val list = JsonArray(ids.map { id -> buildJsonObject { put("id", id); put("type", "user") } })
        updateField(field, list, list)
    }

    fun setDate(field: MetadataField, iso: String?) =
        updateField(field, iso?.let { JsonPrimitive(it) } ?: CLEAR, iso?.let { JsonPrimitive(it) } ?: JsonNull)

    private val _people = MutableStateFlow<List<User>>(emptyList())
    /** Project members (plus the uploader) to resolve and pick users in metadata fields. */
    val people = _people.asStateFlow()
    private var peopleFor: String? = null

    fun loadPeople() {
        val f = state.value.file ?: return
        if (peopleFor == f.projectId) return
        peopleFor = f.projectId
        scope.launch {
            val members = runCatching { repo.members(account, "projects", f.projectId).map { it.user } }.getOrDefault(emptyList())
            val extra = listOfNotNull(f.creator, state.value.me)
            _people.value = (members + extra).filter { it.id != null }.distinctBy { it.id }
        }
    }

    /** Frame.io rejects null when clearing a value; an empty list clears any field type. */
    private val CLEAR = JsonArray(emptyList())

    private fun updateField(field: MetadataField, wire: JsonElement, local: JsonElement) {
        val file = state.value.file ?: return
        val previous = file.metadata
        state.update { s ->
            s.copy(file = s.file?.copy(metadata = s.file.metadata.map {
                if (it.definitionId == field.definitionId) it.copy(value = local) else it
            }))
        }
        fullMeta = fullMeta?.map { if (it.definitionId == field.definitionId) it.copy(value = local) else it }
        scope.launch {
            runCatching {
                val clearing = wire === CLEAR || wire is JsonNull
                if (clearing && field.fieldType !in setOf("select", "select_multi", "user_single", "user_multi", "users")) {
                    repo.clearMetadata(account, file.projectId, file.id, field.definitionId)
                } else {
                    repo.updateMetadata(account, file.projectId, listOf(file.id), field.definitionId, wire)
                }
            }
                .onFailure {
                    state.update { s -> s.copy(file = s.file?.copy(metadata = previous)) }
                    messages.emit(it.userMessage())
                }
        }
    }
}
