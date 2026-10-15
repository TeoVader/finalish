package dev.finalish.core.network

import android.content.Context
import dev.finalish.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Features each account's Frame.io plan doesn't include. The API has no plan endpoint, so
 * these come from two places: a Free account is recognised by its 2 GB storage limit (and
 * gets Free's known gaps from frame.io/pricing), and any 422 "feature(s) not included in
 * plan: …" answer is remembered. Screens grey out what would fail.
 */
object PlanLimits {
    /** Free's storage limit; Pro starts at 2 TB. */
    private const val FREE_STORAGE = 2_000_000_000L

    /** What frame.io/pricing lists as missing on Free (keys as the API names them where known). */
    val FREE_MISSING = setOf(
        "share_link_expiration", "password_protected_shares", "secure_sharing", "comment_attachments",
        "share_disable_downloads", "share_disable_comments", "share_hide_versions",
        "custom_branding", "watermark", "restricted_projects", "internal_comments", "user_groups", "multiple_workspaces",
    )

    // Frame.io's feature keys, in words people use.
    private val NAMES = mapOf(
        "share_link_expiration" to "expiring share links",
        "password_protected_shares" to "passphrase-protected share links",
        "secure_sharing" to "invite-only share links",
        "comment_attachments" to "comment attachments",
        "watermark" to "watermarks",
        "forensic_watermark" to "forensic watermarks",
        "user_groups" to "user groups",
        "custom_metadata" to "custom metadata fields",
    )

    /** Free allows two projects; going over gets the account locked (Frame.io's "3 of 2" email). */
    const val FREE_PROJECTS = 2
    // Seeded from account loads and updated from OkHttp threads: thread-safe set, atomic updates.
    private val free: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** How many projects the account may have, when known. */
    /** True once the account was seen with Free's storage limit. */
    fun isFreeAccount(accountId: String?): Boolean = accountId != null && accountId in free

    fun projectLimit(accountId: String?): Int? = FREE_PROJECTS.takeIf { accountId != null && accountId in free }

    private val _missing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val missing: StateFlow<Map<String, Set<String>>> = _missing.asStateFlow()

    fun record(accountId: String, features: Collection<String>) {
        if (features.isEmpty()) return
        // Kept for this run only: an upgraded plan shouldn't stay greyed out.
        _missing.update { all ->
            val now = all[accountId].orEmpty() + features
            if (now == all[accountId]) all else all + (accountId to now)
        }
    }

    /** Free plan, told by its storage limit. */
    fun isFree(storageLimit: Long?): Boolean = storageLimit != null && storageLimit in 1..FREE_STORAGE

    /** Called with each account as it loads: a Free one gets Free's gaps up front. */
    fun seed(accountId: String, storageLimit: Long?) {
        if (isFree(storageLimit)) {
            free += accountId
            record(accountId, FREE_MISSING)
        } else {
            free -= accountId
        }
    }

    fun lacks(accountId: String?, feature: String): Boolean = accountId != null && feature in _missing.value[accountId].orEmpty()

    fun label(feature: String): String = NAMES[feature] ?: feature.replace('_', ' ')

    /** [NAMES] in the app's language. */
    private val NAME_RES = mapOf(
        "share_link_expiration" to R.string.core_plan_feature_share_link_expiration,
        "password_protected_shares" to R.string.core_plan_feature_password_protected_shares,
        "secure_sharing" to R.string.core_plan_feature_secure_sharing,
        "comment_attachments" to R.string.core_plan_feature_comment_attachments,
        "watermark" to R.string.core_plan_feature_watermark,
        "forensic_watermark" to R.string.core_plan_feature_forensic_watermark,
        "user_groups" to R.string.core_plan_feature_user_groups,
        "custom_metadata" to R.string.core_plan_feature_custom_metadata,
    )

    /** [label] in the app's language (unknown keys stay as the API names them). */
    fun label(context: Context, feature: String): String = NAME_RES[feature]?.let(context::getString) ?: feature.replace('_', ' ')

    /** Every feature named in an error body: "x not included in plan" and "…not included in plan: a, b". */
    fun featuresIn(body: String?): List<String> {
        if (body.isNullOrBlank()) return emptyList()
        val details = runCatching {
            AppJson.parseToJsonElement(body).jsonObject["errors"]?.jsonArray?.mapNotNull { it.jsonObject["detail"]?.jsonPrimitive?.content }
        }.getOrNull() ?: listOf(body)
        return details.flatMap { d ->
            Regex("""not included in plan:\s*([\w,\s]+)""").find(d)?.groupValues?.get(1)?.split(',')?.map { it.trim() }
                ?: listOfNotNull(Regex("""(\w+) not included in plan""").find(d)?.groupValues?.get(1))
        }.filter { it.isNotBlank() }.distinct()
    }

    /** "/v4/accounts/{id}/…" → id. */
    fun accountOf(path: List<String>): String? = path.indexOf("accounts").takeIf { it >= 0 }?.let { path.getOrNull(it + 1) }

    /** "Your Frame.io plan doesn't include expiring share links or passphrase-protected share links." */
    fun message(features: List<String>): String {
        val words = features.map(::label)
        val list = if (words.size <= 1) words.joinToString() else words.dropLast(1).joinToString(", ") + " or " + words.last()
        return "Your Frame.io plan doesn't include $list."
    }

    /** [message] in the app's language. */
    fun message(context: Context, features: List<String>): String {
        val words = features.map { label(context, it) }
        val list = if (words.size <= 1) words.joinToString() else context.getString(R.string.core_plan_list_or, words.dropLast(1).joinToString(", "), words.last())
        return context.getString(R.string.core_plan_limit, list)
    }
}
