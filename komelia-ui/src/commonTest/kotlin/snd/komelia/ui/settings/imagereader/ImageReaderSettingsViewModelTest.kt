package snd.komelia.ui.settings.imagereader

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jetbrains.compose.resources.getString
import snd.komelia.AppNotifications
import snd.komelia.image.ReduceKernel
import snd.komelia.image.UpsamplingMode
import snd.komelia.image.UpscaleMode
import snd.komelia.settings.ImageReaderSettingsRepository
import snd.komelia.settings.model.ContinuousReadingDirection
import snd.komelia.settings.model.LayoutScaleType
import snd.komelia.settings.model.PageDisplayLayout
import snd.komelia.settings.model.PagedReadingDirection
import snd.komelia.settings.model.PanelTransitionMode
import snd.komelia.settings.model.ReaderFlashColor
import snd.komelia.settings.model.ReaderType
import snd.komelia.ui.strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ImageReaderSettingsViewModelTest {
    @Test
    fun `panel transition selection persists once and repository emissions stay live`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = TestImageReaderSettingsRepository()
            val viewModel = imageReaderSettingsViewModel(repository)
            runCurrent()

            viewModel.onPanelTransitionModeChange(PanelTransitionMode.CLASSIC)
            runCurrent()

            assertEquals(listOf(PanelTransitionMode.CLASSIC), repository.writtenModes)
            assertEquals(PanelTransitionMode.CLASSIC, viewModel.panelTransitionMode.value)

            repository.panelTransitionMode.value = PanelTransitionMode.SNAPPY
            runCurrent()

            assertEquals(PanelTransitionMode.SNAPPY, viewModel.panelTransitionMode.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `panel transition labels are exactly Snappy and Classic`() = runTest {
        assertEquals(
            "Snappy",
            getString(AppStrings.forPanelTransitionMode(PanelTransitionMode.SNAPPY)),
        )
        assertEquals(
            "Classic",
            getString(AppStrings.forPanelTransitionMode(PanelTransitionMode.CLASSIC)),
        )
    }

    private fun imageReaderSettingsViewModel(repository: ImageReaderSettingsRepository) =
        ImageReaderSettingsViewModel(
            settingsRepository = repository,
            appNotifications = AppNotifications(),
            onnxRuntimeInstaller = null,
            onnxRuntime = null,
            upscaler = null,
            panelDetector = null,
            onnxModelDownloader = null,
            coilMemoryCache = null,
            coilDiskCache = null,
            readerDiskCache = null,
        )
}

private class TestImageReaderSettingsRepository : ImageReaderSettingsRepository {
    val panelTransitionMode = MutableStateFlow(PanelTransitionMode.SNAPPY)
    val writtenModes = mutableListOf<PanelTransitionMode>()

    override fun getPanelTransitionMode(): Flow<PanelTransitionMode> = panelTransitionMode

    override suspend fun putPanelTransitionMode(mode: PanelTransitionMode) {
        writtenModes += mode
        panelTransitionMode.value = mode
    }

    override fun getReaderType(): Flow<ReaderType> = unused()
    override suspend fun putReaderType(type: ReaderType) = unused()
    override fun getStretchToFit(): Flow<Boolean> = unused()
    override suspend fun putStretchToFit(stretch: Boolean) = unused()
    override fun getCropBorders(): Flow<Boolean> = unused()
    override suspend fun putCropBorders(trim: Boolean) = unused()
    override fun getPagedReaderScaleType(): Flow<LayoutScaleType> = unused()
    override suspend fun putPagedReaderScaleType(type: LayoutScaleType) = unused()
    override fun getPagedReaderReadingDirection(): Flow<PagedReadingDirection> = unused()
    override suspend fun putPagedReaderReadingDirection(direction: PagedReadingDirection) = unused()
    override fun getPagedReaderDisplayLayout(): Flow<PageDisplayLayout> = unused()
    override suspend fun putPagedReaderDisplayLayout(layout: PageDisplayLayout) = unused()
    override fun getContinuousReaderReadingDirection(): Flow<ContinuousReadingDirection> = unused()
    override suspend fun putContinuousReaderReadingDirection(direction: ContinuousReadingDirection) = unused()
    override fun getContinuousReaderPadding(): Flow<Float> = unused()
    override suspend fun putContinuousReaderPadding(padding: Float) = unused()
    override fun getContinuousReaderPageSpacing(): Flow<Int> = unused()
    override suspend fun putContinuousReaderPageSpacing(spacing: Int) = unused()
    override fun getFlashOnPageChange(): Flow<Boolean> = unused()
    override suspend fun putFlashOnPageChange(flash: Boolean) = unused()
    override fun getFlashDuration(): Flow<Long> = unused()
    override suspend fun putFlashDuration(duration: Long) = unused()
    override fun getFlashEveryNPages(): Flow<Int> = unused()
    override suspend fun putFlashEveryNPages(pages: Int) = unused()
    override fun getFlashWith(): Flow<ReaderFlashColor> = unused()
    override suspend fun putFlashWith(color: ReaderFlashColor) = unused()
    override fun getDownsamplingKernel(): Flow<ReduceKernel> = unused()
    override suspend fun putDownsamplingKernel(kernel: ReduceKernel) = unused()
    override fun getLinearLightDownsampling(): Flow<Boolean> = unused()
    override suspend fun putLinearLightDownsampling(linear: Boolean) = unused()
    override fun getUpsamplingMode(): Flow<UpsamplingMode> = unused()
    override suspend fun putUpsamplingMode(mode: UpsamplingMode) = unused()
    override fun getLoadThumbnailPreviews(): Flow<Boolean> = unused()
    override suspend fun putLoadThumbnailPreviews(load: Boolean) = unused()
    override fun getVolumeKeysNavigation(): Flow<Boolean> = unused()
    override suspend fun putVolumeKeysNavigation(enable: Boolean) = unused()
    override fun getUpscalerMode(): Flow<UpscaleMode> = unused()
    override suspend fun putUpscalerMode(mode: UpscaleMode) = unused()
    override fun getOnnxRuntimeDeviceId(): Flow<Int> = unused()
    override suspend fun putOnnxRuntimeDeviceId(deviceId: Int) = unused()
    override fun getOnnxRuntimeTileSize(): Flow<Int> = unused()
    override suspend fun putOnnxRuntimeTileSize(tileSize: Int) = unused()
    override fun getUpscalerOnnxModel(): Flow<PlatformFile?> = unused()
    override suspend fun putUpscalerOnnxModel(name: PlatformFile?) = unused()

    private fun unused(): Nothing = error("not used")
}
