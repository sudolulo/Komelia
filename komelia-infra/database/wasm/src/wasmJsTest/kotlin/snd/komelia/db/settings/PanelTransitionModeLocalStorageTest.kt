package snd.komelia.db.settings

import kotlinx.browser.localStorage
import snd.komelia.db.ImageReaderSettings
import snd.komelia.settings.model.PanelTransitionMode
import kotlin.test.Test
import kotlin.test.assertEquals

class PanelTransitionModeLocalStorageTest {

    @Test
    fun `local storage round trips both panel transition modes`() {
        val repository = LocalStorageSettingsRepository()
        try {
            PanelTransitionMode.entries.forEach { mode ->
                repository.saveImageReaderSettings(ImageReaderSettings(panelTransitionMode = mode))
                assertEquals(mode, repository.getImageReaderSettings().panelTransitionMode)
            }
        } finally {
            localStorage.removeItem(imageReaderKey)
        }
    }

    @Test
    fun `absent local storage entry defaults to snappy`() {
        val repository = LocalStorageSettingsRepository()
        try {
            localStorage.removeItem(imageReaderKey)
            assertEquals(PanelTransitionMode.SNAPPY, repository.getImageReaderSettings().panelTransitionMode)
        } finally {
            localStorage.removeItem(imageReaderKey)
        }
    }
}
