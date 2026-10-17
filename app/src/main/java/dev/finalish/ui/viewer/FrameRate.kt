package dev.finalish.ui.viewer

import dev.finalish.core.model.Asset

/** The file's frame rate from its metadata ("Frame Rate" or "FPS"), for when the decoder hasn't reported one. */
internal fun Asset.metadataFps(): Double? {
    val meta = metadata.firstOrNull { it.name.contains("frame rate", true) || it.name.equals("fps", true) } ?: return null
    return meta.doubleValue ?: meta.textValue?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
}
