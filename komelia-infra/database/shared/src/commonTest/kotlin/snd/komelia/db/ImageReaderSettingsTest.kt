package snd.komelia.db

import kotlinx.serialization.json.Json
import snd.komelia.settings.model.PanelTransitionMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageReaderSettingsTest {
    @Test
    fun `panel transition mode defaults to snappy`() {
        assertEquals(PanelTransitionMode.SNAPPY, ImageReaderSettings().panelTransitionMode)
    }

    @Test
    fun `panel transition mode serializes both values`() {
        PanelTransitionMode.entries.forEach { mode ->
            val encoded = Json.encodeToString(ImageReaderSettings(panelTransitionMode = mode))
            assertEquals(mode, Json.decodeFromString<ImageReaderSettings>(encoded).panelTransitionMode)
        }
    }
}
