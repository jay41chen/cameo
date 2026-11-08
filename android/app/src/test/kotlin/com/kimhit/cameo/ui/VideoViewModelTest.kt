package com.kimhit.cameo.ui

import androidx.lifecycle.SavedStateHandle
import com.kimhit.cameo.data.network.DataConfig
import com.kimhit.cameo.domain.model.Cameo
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.CameoShare
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainError
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.usecase.GetCameoUseCase
import com.kimhit.cameo.domain.usecase.ShareCameoUseCase
import com.kimhit.cameo.domain.usecase.UnshareCameoUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun videoOrder(status: CameoStatus = CameoStatus.SUCCEEDED, share: CameoShare? = CameoShare(false, null)) =
    CameoOrder(
        Cameo("u1", Clip("c1", "雨夜重逢", 10), status, "標題", "說明", videoAvailable = status == CameoStatus.SUCCEEDED, share = share),
        null,
    )

private fun <T> videoOk(o: T): DomainResult<T> = DomainResult.Success(o)

private fun videoFail(code: String? = null, message: String? = "後端訊息") =
    DomainResult.Failure(DomainError(message, false, code))

@OptIn(ExperimentalCoroutinesApi::class)
class VideoViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val cameos = FakeCameoRepo()
    private val shareRepo = FakeShareRepo()

    private fun vm() = VideoViewModel(
        SavedStateHandle(mapOf("cameoId" to "u1")),
        GetCameoUseCase(cameos),
        ShareCameoUseCase(shareRepo),
        UnshareCameoUseCase(shareRepo),
        DataConfig("http://10.0.2.2:8080/", "t"),
        OkHttpClient(),
    )

    private fun getCount() = cameos.calls.count { it == "get" }

    private fun VideoViewModel.ready() = state.value as VideoUiState.Ready

    @Test
    fun succeeded_isReady_andVideoUrlIsBuilt() = runTest {
        cameos.gets["u1"] = videoOk(videoOrder())
        val vm = vm()
        assertEquals(VideoUiState.Loading, vm.state.value)
        advanceUntilIdle()
        assertTrue(vm.state.value is VideoUiState.Ready)
        assertEquals("http://10.0.2.2:8080/v1/cameos/u1/video", vm.videoUrl)
    }

    @Test
    fun notSucceeded_isNotReadyWithDisplay() = runTest {
        cameos.gets["u1"] = videoOk(videoOrder(CameoStatus.GENERATING))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(VideoUiState.NotReady("標題", "說明"), vm.state.value)
    }

    @Test
    fun notFound_isNotFound() = runTest {
        cameos.gets["u1"] = videoFail("CAMEO_NOT_FOUND")
        val vm = vm()
        advanceUntilIdle()
        assertEquals(VideoUiState.NotFound, vm.state.value)
    }

    @Test
    fun networkError_isLoadError_thenRetryLoads() = runTest {
        cameos.gets["u1"] = videoFail(message = null)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(VideoUiState.LoadError(GENERIC_ERROR), vm.state.value)
        cameos.gets["u1"] = videoOk(videoOrder())
        vm.retry()
        assertEquals(VideoUiState.Loading, vm.state.value)
        advanceUntilIdle()
        assertTrue(vm.state.value is VideoUiState.Ready)
        assertEquals(2, getCount())
    }

    @Test
    fun share_success_callsPutOnce_setsUrl_thenOnShareSentClears() = runTest {
        cameos.gets["u1"] = videoOk(videoOrder())
        shareRepo.shares += videoOk(CameoShare(true, "http://x/s/A"))
        val vm = vm()
        advanceUntilIdle()
        vm.share()
        advanceUntilIdle()
        assertEquals(1, shareRepo.shareCalls)
        assertEquals("http://x/s/A", vm.ready().shareUrlToSend)
        assertEquals(CameoShare(true, "http://x/s/A"), vm.ready().cameo.share)
        assertFalse(vm.ready().busy)
        vm.onShareSent()
        assertNull(vm.ready().shareUrlToSend)
    }

    @Test
    fun share_alreadyActive_usesPutUrlNotGetUrl_andPutsEveryTime() = runTest {
        cameos.gets["u1"] = videoOk(videoOrder(share = CameoShare(true, "http://x/s/A")))
        shareRepo.shares += videoOk(CameoShare(true, "http://x/s/B"))
        shareRepo.shares += videoOk(CameoShare(true, "http://x/s/B"))
        val vm = vm()
        advanceUntilIdle()
        vm.share()
        advanceUntilIdle()
        assertEquals("http://x/s/B", vm.ready().shareUrlToSend)
        vm.onShareSent()
        vm.share()
        advanceUntilIdle()
        assertEquals(2, shareRepo.shareCalls)
        assertEquals("http://x/s/B", vm.ready().shareUrlToSend)
    }

    @Test
    fun share_successWithNullOrEmptyUrl_doesNotSend_andShowsMessage() = runTest {
        cameos.gets["u1"] = videoOk(videoOrder())
        shareRepo.shares += videoOk(CameoShare(true, null))
        shareRepo.shares += videoOk(CameoShare(true, ""))
        val vm = vm()
        advanceUntilIdle()
        vm.share()
        advanceUntilIdle()
        assertNull(vm.ready().shareUrlToSend)
        assertEquals(GENERIC_ERROR, vm.ready().message)
        assertFalse(vm.ready().busy)
        vm.dismissMessage()
        assertNull(vm.ready().message)
        vm.share()
        advanceUntilIdle()
        assertNull(vm.ready().shareUrlToSend)
        assertEquals(GENERIC_ERROR, vm.ready().message)
    }

    @Test
    fun share_notShareable_refetches_andShowsBackendMessage() = runTest {
        cameos.gets["u1"] = videoOk(videoOrder())
        shareRepo.shares += videoFail("NOT_SHAREABLE", "影片尚未完成")
        val vm = vm()
        advanceUntilIdle()
        vm.share()
        advanceUntilIdle()
        assertEquals(2, getCount())
        assertEquals("影片尚未完成", vm.ready().message)
        assertFalse(vm.ready().busy)
        assertNull(vm.ready().shareUrlToSend)
    }

    @Test
    fun share_notShareable_refetchNotSucceeded_isNotReady() = runTest {
        cameos.gets["u1"] = videoOk(videoOrder())
        shareRepo.shares += videoFail("NOT_SHAREABLE")
        val vm = vm()
        advanceUntilIdle()
        cameos.gets["u1"] = videoOk(videoOrder(CameoStatus.FAILED))
        vm.share()
        advanceUntilIdle()
        assertEquals(2, getCount())
        assertEquals(VideoUiState.NotReady("標題", "說明"), vm.state.value)
    }

    @Test
    fun share_otherFailure_keepsShare_andShowsMessage() = runTest {
        cameos.gets["u1"] = videoOk(videoOrder(share = CameoShare(true, "http://x/s/A")))
        shareRepo.shares += videoFail(message = null)
        val vm = vm()
        advanceUntilIdle()
        vm.share()
        advanceUntilIdle()
        assertEquals(CameoShare(true, "http://x/s/A"), vm.ready().cameo.share)
        assertEquals(GENERIC_ERROR, vm.ready().message)
        assertNull(vm.ready().shareUrlToSend)
        assertEquals(1, getCount())
    }

    @Test
    fun share_whileBusy_doesNotCallAgain() = runTest {
        val gate = CompletableDeferred<Unit>()
        cameos.gets["u1"] = videoOk(videoOrder())
        shareRepo.shares += videoOk(CameoShare(true, "http://x/s/A"))
        val gated = object : com.kimhit.cameo.domain.repository.CameoShareRepository by shareRepo {
            override suspend fun share(cameoId: String): DomainResult<CameoShare> {
                gate.await()
                return shareRepo.share(cameoId)
            }
        }
        val vm = VideoViewModel(
            SavedStateHandle(mapOf("cameoId" to "u1")),
            GetCameoUseCase(cameos),
            ShareCameoUseCase(gated),
            UnshareCameoUseCase(gated),
            DataConfig("http://h", "t"),
            OkHttpClient(),
        )
        advanceUntilIdle()
        vm.share()
        advanceUntilIdle()
        assertTrue(vm.ready().busy)
        vm.share()
        vm.unshare()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, shareRepo.shareCalls)
        assertEquals(0, shareRepo.unshareCalls)
    }

    @Test
    fun unshare_whileBusy_doesNotCallAgain() = runTest {
        val gate = CompletableDeferred<Unit>()
        cameos.gets["u1"] = videoOk(videoOrder(share = CameoShare(true, "http://x/s/A")))
        shareRepo.unshares += DomainResult.Success(Unit)
        val gated = object : com.kimhit.cameo.domain.repository.CameoShareRepository by shareRepo {
            override suspend fun unshare(cameoId: String): DomainResult<Unit> {
                gate.await()
                return shareRepo.unshare(cameoId)
            }
        }
        val vm = VideoViewModel(
            SavedStateHandle(mapOf("cameoId" to "u1")),
            GetCameoUseCase(cameos),
            ShareCameoUseCase(gated),
            UnshareCameoUseCase(gated),
            DataConfig("http://h", "t"),
            OkHttpClient(),
        )
        advanceUntilIdle()
        vm.unshare()
        advanceUntilIdle()
        assertTrue(vm.ready().busy)
        vm.unshare()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, shareRepo.unshareCalls)
    }

    @Test
    fun unshare_success_makesShareInactive() = runTest {
        cameos.gets["u1"] = videoOk(videoOrder(share = CameoShare(true, "http://x/s/A")))
        shareRepo.unshares += DomainResult.Success(Unit)
        val vm = vm()
        advanceUntilIdle()
        vm.unshare()
        advanceUntilIdle()
        assertEquals(1, shareRepo.unshareCalls)
        assertEquals(CameoShare(false, null), vm.ready().cameo.share)
        assertFalse(vm.ready().busy)
    }

    @Test
    fun unshare_failure_keepsActive_andShowsMessage() = runTest {
        cameos.gets["u1"] = videoOk(videoOrder(share = CameoShare(true, "http://x/s/A")))
        shareRepo.unshares += videoFail(message = "取消失敗")
        val vm = vm()
        advanceUntilIdle()
        vm.unshare()
        advanceUntilIdle()
        assertEquals(CameoShare(true, "http://x/s/A"), vm.ready().cameo.share)
        assertEquals("取消失敗", vm.ready().message)
        assertFalse(vm.ready().busy)
    }

    @Test
    fun unshare_notActive_doesNotCall() = runTest {
        cameos.gets["u1"] = videoOk(videoOrder(share = CameoShare(false, null)))
        val vm = vm()
        advanceUntilIdle()
        vm.unshare()
        advanceUntilIdle()
        assertEquals(0, shareRepo.unshareCalls)
    }

    @Test
    fun canOpenVideo_followsVideoAvailable() {
        val c = videoOrder().cameo
        assertTrue(canOpenVideo(c.copy(videoAvailable = true)))
        assertFalse(canOpenVideo(c.copy(videoAvailable = false)))
    }
}
