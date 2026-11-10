package io.github.jay41chen.cameo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.jay41chen.cameo.domain.model.Cameo
import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.CameoPage
import io.github.jay41chen.cameo.domain.model.CameoShare
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.PendingCameo
import io.github.jay41chen.cameo.domain.model.PendingStep
import io.github.jay41chen.cameo.domain.model.Quota
import io.github.jay41chen.cameo.domain.repository.CameoRepository
import io.github.jay41chen.cameo.domain.repository.CameoShareRepository
import io.github.jay41chen.cameo.domain.repository.ClipRepository
import io.github.jay41chen.cameo.domain.repository.PendingCameoRepository
import io.github.jay41chen.cameo.domain.repository.QuotaRepository
import io.github.jay41chen.cameo.tracking.SubmissionKeepAlive
import kotlinx.coroutines.CompletableDeferred

class FakeClips(val results: ArrayDeque<DomainResult<List<Clip>>>) : ClipRepository {
    override suspend fun getClips() = results.removeFirst()
}

/** 每次呼叫取出一個 suspend lambda 執行，方便測試延遲與取消。 */
class FakeQuota(val queue: ArrayDeque<suspend () -> DomainResult<Quota>> = ArrayDeque()) : QuotaRepository {
    var calls = 0
    override suspend fun getQuota(): DomainResult<Quota> {
        calls++
        return queue.removeFirst()()
    }
}

class FakeCameoRepo(
    val creates: ArrayDeque<DomainResult<CameoOrder>> = ArrayDeque(),
    val gets: MutableMap<String, DomainResult<CameoOrder>> = mutableMapOf(),
) : CameoRepository {
    val keys = mutableListOf<String>()
    val uploads = ArrayDeque<DomainResult<CameoOrder>>()
    val submits = ArrayDeque<DomainResult<CameoOrder>>()
    val calls = mutableListOf<String>()
    override suspend fun createCameo(clientRequestId: String, clipId: String): DomainResult<CameoOrder> {
        keys += clientRequestId
        return creates.removeFirst()
    }

    override suspend fun getCameo(id: String): DomainResult<CameoOrder> {
        calls += "get"
        return gets[id] ?: error("no stub for $id")
    }

    override suspend fun uploadSelfie(url: String, photoPath: String): DomainResult<CameoOrder> {
        calls += "upload"
        return uploads.removeFirst()
    }

    override suspend fun submitCameo(cameoId: String): DomainResult<CameoOrder> {
        calls += "submit"
        return submits.removeFirst()
    }

    override suspend fun listCameos(cursor: String?): DomainResult<CameoPage> = error("listCameos not stubbed")
}

class FakePendingRepo : PendingCameoRepository {
    val rows = mutableListOf<PendingCameo>()
    private var next = 1

    override suspend fun getOrCreateDraft(clipId: String): PendingCameo {
        rows.firstOrNull { it.clipId == clipId && (it.step == PendingStep.DRAFT || it.step == PendingStep.CREATE) }
            ?.let { return it }
        return PendingCameo("key-${next++}", clipId, null, PendingStep.DRAFT, null).also { rows += it }
    }

    override suspend fun markConfirming(clientRequestId: String) {
        val i = rows.indexOfFirst { it.clientRequestId == clientRequestId && it.step == PendingStep.DRAFT }
        if (i >= 0) rows[i] = rows[i].copy(step = PendingStep.CREATE)
    }

    /** 相機頁「使用這張」之後的狀態：該片段已有附上照片的草稿（沒有草稿才新增）。 */
    fun seedDraftWithPhoto(clipId: String): PendingCameo {
        rows.firstOrNull { it.clipId == clipId && (it.step == PendingStep.DRAFT || it.step == PendingStep.CREATE) }
            ?.let { return it }
        val key = "key-${next++}"
        return PendingCameo(key, clipId, null, PendingStep.DRAFT, null, "/photos/$key.jpg").also { rows += it }
    }

    override suspend fun markCreated(clientRequestId: String, cameoId: String) {
        val i = rows.indexOfFirst { it.clientRequestId == clientRequestId }
        if (i >= 0) rows[i] = rows[i].copy(cameoId = cameoId, step = PendingStep.UPLOAD, lastError = null)
    }

    override suspend fun recordError(clientRequestId: String, message: String?) {
        val i = rows.indexOfFirst { it.clientRequestId == clientRequestId }
        if (i >= 0) rows[i] = rows[i].copy(lastError = message)
    }

    override suspend fun delete(clientRequestId: String) {
        rows.removeAll { it.clientRequestId == clientRequestId }
    }

    val claims = mutableListOf<String>()

    override suspend fun claimFinishedCameo(cameoId: String): Boolean {
        claims += cameoId
        val removed = rows.removeAll { it.cameoId == cameoId }
        return removed
    }

    override suspend fun deletePhoto(clientRequestId: String) {
        val i = rows.indexOfFirst { it.clientRequestId == clientRequestId }
        if (i >= 0) rows[i] = rows[i].copy(photoPath = null)
    }

    /** 這些來源路徑視為不存在，attachPhoto 回 false。 */
    val missingSources = mutableSetOf<String>()
    val attached = mutableListOf<Pair<String, String>>()

    override suspend fun attachPhoto(clientRequestId: String, sourcePath: String): Boolean {
        if (sourcePath in missingSources) return false
        val i = rows.indexOfFirst { it.clientRequestId == clientRequestId }
        if (i < 0) return false
        attached += clientRequestId to sourcePath
        rows[i] = rows[i].copy(photoPath = "/photos/$clientRequestId.jpg")
        return true
    }

    override suspend fun list(): List<PendingCameo> = rows.toList()

    override suspend fun get(clientRequestId: String): PendingCameo? =
        rows.firstOrNull { it.clientRequestId == clientRequestId }

    override suspend fun markUploaded(clientRequestId: String) {
        val i = rows.indexOfFirst { it.clientRequestId == clientRequestId }
        if (i >= 0) rows[i] = rows[i].copy(step = PendingStep.SUBMIT, photoPath = null, lastError = null)
    }

    override suspend fun markSubmitted(clientRequestId: String) {
        val i = rows.indexOfFirst { it.clientRequestId == clientRequestId }
        if (i >= 0) rows[i] = rows[i].copy(step = PendingStep.TRACK, lastError = null)
    }
}

/** 記錄檔案操作，不碰真實檔案系統。 */
class FakeCaptureFiles(val existing: MutableSet<String> = mutableSetOf()) : CaptureFiles {
    val deleted = mutableListOf<String>()
    var keptByCleanup: String? = "unset"
    private var n = 0

    override fun newFile(): String = "/cache/captures/${++n}.jpg".also { existing += it }
    override fun exists(path: String) = path in existing
    override fun delete(path: String) {
        deleted += path
        existing -= path
    }

    override fun deleteOthers(keep: String?) {
        keptByCleanup = keep
        existing.filter { it != keep }.forEach { delete(it) }
    }
}

fun testOrder(status: CameoStatus = CameoStatus.AWAITING_UPLOAD, id: String = "u1") =
    CameoOrder(Cameo(id, Clip("c1", "雨夜重逢", 10), status, "等待上傳", "已占用 10 點，送出後才扣款"), null)

fun quotaOf(remaining: Int) = Quota(remaining, "2026-11-08T00:00:00+08:00", "今日還可客串 $remaining 次")

/** 記錄 acquire／track／release 的呼叫順序（"acquire:key"、"track:id"、"release:key"）；[onRelease] 供測試在釋放當下取樣。 */
class FakeKeepAlive : SubmissionKeepAlive {
    val events = mutableListOf<String>()
    var onRelease: (String) -> Unit = {}

    override fun acquire(key: String) {
        events += "acquire:$key"
    }

    override fun release(key: String) {
        events += "release:$key"
        onRelease(key)
    }

    override fun track(cameoId: String) {
        events += "track:$cameoId"
    }
}

/** 透過 ViewModelStore 清除 ViewModel，觸發 protected 的 onCleared。 */
fun <T : ViewModel> clearViewModel(vm: T) {
    val store = ViewModelStore()
    @Suppress("UNCHECKED_CAST")
    val factory = object : ViewModelProvider.Factory {
        override fun <V : ViewModel> create(modelClass: Class<V>): V = vm as V
    }
    ViewModelProvider.create(store, factory)[vm.javaClass]
    store.clear()
}

class FakeShareRepo : CameoShareRepository {
    val shares = ArrayDeque<DomainResult<CameoShare>>()
    val unshares = ArrayDeque<DomainResult<Unit>>()
    var shareCalls = 0
    var unshareCalls = 0

    override suspend fun share(cameoId: String): DomainResult<CameoShare> {
        shareCalls++
        return shares.removeFirst()
    }

    override suspend fun unshare(cameoId: String): DomainResult<Unit> {
        unshareCalls++
        return unshares.removeFirst()
    }
}

/** 可以卡住 createCameo、getCameo、submitCameo 與 uploadSelfie，驗證執行中的狀態、不重入與離開頁面後工作仍繼續。 */
internal class GatedCameos(val delegate: FakeCameoRepo) : CameoRepository by delegate {
    var createGate: CompletableDeferred<Unit>? = null
    var getGate: CompletableDeferred<Unit>? = null
    var uploadGate: CompletableDeferred<Unit>? = null
    var submitGate: CompletableDeferred<Unit>? = null
    var getCalls = 0

    override suspend fun createCameo(clientRequestId: String, clipId: String): DomainResult<CameoOrder> {
        createGate?.await()
        return delegate.createCameo(clientRequestId, clipId)
    }

    override suspend fun getCameo(id: String): DomainResult<CameoOrder> {
        getCalls++
        getGate?.await()
        return delegate.getCameo(id)
    }

    override suspend fun submitCameo(cameoId: String): DomainResult<CameoOrder> {
        submitGate?.await()
        return delegate.submitCameo(cameoId)
    }

    override suspend fun uploadSelfie(url: String, photoPath: String): DomainResult<CameoOrder> {
        uploadGate?.await()
        return delegate.uploadSelfie(url, photoPath)
    }
}
