package dev.finalish.ui.browser

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.finalish.R
import dev.finalish.core.model.Asset
import dev.finalish.core.model.MetadataField
import dev.finalish.ui.viewer.isEmpty
import dev.finalish.ui.viewer.prettyValue
import dev.finalish.ui.viewer.userIds

/**
 * Filters and sorting on any field, like the official Filters / Sort by menus. Everything
 * runs on the loaded folder (browse requests already include each file's metadata).
 */
const val NAME_FIELD = "Name"

enum class FilterOp(@StringRes val labelRes: Int, val needsValue: Boolean = true) {
    Contains(R.string.browser_op_contains),
    Is(R.string.browser_op_is),
    IsNot(R.string.browser_op_is_not),
    Greater(R.string.browser_op_greater),
    Less(R.string.browser_op_less),
    Empty(R.string.browser_op_empty, needsValue = false),
    NotEmpty(R.string.browser_op_not_empty, needsValue = false),
}

data class FieldFilter(val field: String, val op: FilterOp, val value: String = "") {
    /** "Bitrate > 20", "Status is Approved", "Notes is empty". */
    @Composable
    fun label(): String {
        val opLabel = stringResource(op.labelRes)
        return if (op.needsValue) "$field $opLabel $value" else "$field $opLabel"
    }
}

/** A field as it appears across the folder: its name, type and the options seen. */
data class FieldInfo(val name: String, val type: String, val options: List<String> = emptyList())

private val NUMERIC = setOf("number", "rating")
private val CHOICE = setOf("select", "select_multi", "toggle", "user_single", "user_multi", "users")

fun opsFor(type: String): List<FilterOp> = when (type) {
    in NUMERIC -> listOf(FilterOp.Greater, FilterOp.Less, FilterOp.Is, FilterOp.Empty, FilterOp.NotEmpty)
    in CHOICE -> listOf(FilterOp.Is, FilterOp.IsNot, FilterOp.Empty, FilterOp.NotEmpty)
    else -> listOf(FilterOp.Contains, FilterOp.Is, FilterOp.IsNot, FilterOp.Empty, FilterOp.NotEmpty)
}

/** Every field present on the files of a folder, Name first, then alphabetical. */
fun fieldsIn(items: List<Asset>): List<FieldInfo> {
    val byName = linkedMapOf<String, FieldInfo>()
    items.filterNot { it.isFolder }.forEach { a ->
        a.metadata.forEach { f ->
            val opts = when (f.fieldType) {
                "select", "select_multi" -> f.options.map { it.displayName } + f.selectedOptions.map { it.displayName }
                "toggle" -> listOf("Yes", "No")
                else -> emptyList()
            }
            val old = byName[f.name]
            byName[f.name] = FieldInfo(f.name, f.fieldType, ((old?.options ?: emptyList()) + opts).distinct())
        }
    }
    return listOf(FieldInfo(NAME_FIELD, "text")) + byName.values.sortedBy { it.name.lowercase() }
}

private fun Asset.field(name: String): MetadataField? = metadata.firstOrNull { it.name == name }

/** The value a person reads on the card (Duration as 1:23, toggles as Yes/No). */
fun Asset.fieldText(
    name: String,
    /** "3 people": the UI passes the translated plural; sorting only needs a stable text. */
    people: (Int) -> String = { "$it ${if (it == 1) "person" else "people"}" },
): String? =
    if (name == NAME_FIELD) this.name
    else field(name)?.let { f ->
        when {
            f.fieldType in setOf("user_single", "user_multi", "users") -> f.userIds().size.takeIf { it > 0 }?.let(people)
            f.isEmpty() -> null
            else -> f.prettyValue()
        }
    }

private fun Asset.fieldNumber(name: String): Double? = field(name)?.let { it.doubleValue ?: it.intValue?.toDouble() }

private fun Asset.isFieldEmpty(name: String): Boolean =
    if (name == NAME_FIELD) this.name.isBlank() else field(name)?.isEmpty() ?: true

fun FieldFilter.matches(a: Asset): Boolean {
    // Folders aren't described by file fields; filters only narrow the files.
    if (a.isFolder) return field == NAME_FIELD && op.test(a.name, null, value)
    if (op == FilterOp.Empty) return a.isFieldEmpty(field)
    if (op == FilterOp.NotEmpty) return !a.isFieldEmpty(field)
    val raw = if (field == NAME_FIELD) a.name else a.field(field)?.let { f ->
        when (f.fieldType) {
            "select", "select_multi" -> f.selectedOptions.joinToString("\n") { it.displayName }
            "toggle" -> when (f.boolValue) { true -> "Yes"; false -> "No"; null -> null }
            else -> f.textValue
        }
    }
    return op.test(raw, if (field == NAME_FIELD) null else a.fieldNumber(field), value)
}

private fun FilterOp.test(text: String?, number: Double?, value: String): Boolean {
    val v = value.trim()
    val target = v.replace(',', '.').toDoubleOrNull()
    val options = text?.split('\n').orEmpty()
    return when (this) {
        FilterOp.Contains -> text?.contains(v, ignoreCase = true) == true
        FilterOp.Is -> if (number != null && target != null) number == target else options.any { it.equals(v, ignoreCase = true) }
        FilterOp.IsNot -> !(if (number != null && target != null) number == target else options.any { it.equals(v, ignoreCase = true) })
        FilterOp.Greater -> number != null && target != null && number > target
        FilterOp.Less -> number != null && target != null && number < target
        FilterOp.Empty -> text.isNullOrBlank()
        FilterOp.NotEmpty -> !text.isNullOrBlank()
    }
}

/** Sort by a field: numbers as numbers, everything else as text; files without a value go last either way. */
fun fieldComparator(name: String, descending: Boolean): Comparator<Asset> = Comparator { a, b ->
    val na = a.fieldNumber(name); val nb = b.fieldNumber(name)
    val ta = a.fieldText(name); val tb = b.fieldText(name)
    val emptyA = na == null && ta.isNullOrBlank(); val emptyB = nb == null && tb.isNullOrBlank()
    when {
        emptyA && emptyB -> 0
        emptyA -> 1
        emptyB -> -1
        else -> {
            val c = if (na != null && nb != null) na.compareTo(nb) else ta.orEmpty().compareTo(tb.orEmpty(), ignoreCase = true)
            if (descending) -c else c
        }
    }
}
