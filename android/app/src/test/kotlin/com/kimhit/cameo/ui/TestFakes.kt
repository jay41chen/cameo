package com.kimhit.cameo.ui

import com.kimhit.cameo.domain.model.Cameo
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.PendingCameo
import com.kimhit.cameo.domain.model.PendingStep
import com.kimhit.cameo.domain.model.Quota
import com.kimhit.cameo.domain.repository.CameoRepository
import com.kimhit.cameo.domain.repository.ClipRepository
import com.kimhit.cameo.domain.repository.PendingCameoRepository
import com.kimhit.cameo.domain.repository.QuotaRepository

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
    override suspend fun createCameo(clientRequestId: String, clipId: String): DomainResult<CameoOrder> {
        keys += clientRequestId
        return creates.removeFirst()
    }

    override suspend fun getCameo(id: String): DomainResult<CameoOrder> =
        gets[id] ?: error("no stub for $id")
}

class FakePendingRepo : PendingCameoRepository {
    val rows = mutableListOf<PendingCameo>()
    private var next = 1

    override suspend fun getOrCreateDraft(clipId: String): PendingCameo {
        rows.firstOrNull { it.clipId == clipId && it.step == PendingStep.CREATE }?.let { return it }
        return PendingCameo("key-${next++}", clipId, null, PendingStep.CREATE, null).also { rows += it }
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
