package dev.finalish.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * "Matching app icon": exactly one launcher alias (.Icon<Accent> in the manifest) is enabled.
 * The default is Glicine, which is also what wallpaper colours and "off" fall back to.
 */
object AppIcon {
    fun apply(context: Context, match: Boolean, accent: String, dynamic: Boolean) {
        val wanted = if (match && !dynamic && AccentTheme.entries.any { it.name == accent }) accent else AccentTheme.Glicine.name
        val pm = context.packageManager
        // Enable the new one first so the app always has a launcher entry.
        AccentTheme.entries.sortedBy { it.name != wanted }.forEach { t ->
            val component = ComponentName(context, "dev.finalish.Icon${t.name}")
            val state = if (t.name == wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (pm.getComponentEnabledSetting(component) == state) return@forEach
            if (t.name == AccentTheme.Glicine.name && state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED &&
                pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            ) return@forEach // enabled by the manifest already
            runCatching { pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP) }
        }
    }
}
