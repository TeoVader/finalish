package dev.finalish.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ExifPeekTest {

    @Test fun readsLikeACameraBack() {
        val tags = mapOf(
            "Make" to "SONY", "Model" to "ILCE-7M4", "LensModel" to "FE 35mm F1.8",
            "ExposureTime" to "0.008", "FNumber" to "28/10", "PhotographicSensitivity" to "400", "FocalLength" to "35/1",
            "DateTimeOriginal" to "2026:10:01 11:48:07",
        )
        assertEquals(
            listOf(
                "Camera" to "SONY ILCE-7M4",
                "Lens" to "FE 35mm F1.8",
                "Exposure" to "1/125 s · f/2.8 · ISO 400 · 35 mm",
                "Taken" to "2026-10-01 11:48",
            ),
            ExifPeek.describe { tags[it] },
        )
    }

    @Test fun modelThatNamesTheMakeIsntRepeated() {
        assertEquals(listOf("Camera" to "Canon EOS R5"), ExifPeek.describe { mapOf("Make" to "Canon", "Model" to "Canon EOS R5")[it] })
    }

    @Test fun longExposure() {
        assertEquals(listOf("Exposure" to "2 s"), ExifPeek.describe { mapOf("ExposureTime" to "2")[it] })
    }

    @Test fun nothing() {
        assertEquals(emptyList<Pair<String, String>>(), ExifPeek.describe { null })
    }
}
