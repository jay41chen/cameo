package com.kimhit.cameo.data

import com.kimhit.cameo.data.cameos.CameoRepositoryImpl
import com.kimhit.cameo.data.network.DataConfig
import com.kimhit.cameo.data.network.createApiService
import com.kimhit.cameo.data.network.createMainClient
import com.kimhit.cameo.data.network.createUploadClient
import com.kimhit.cameo.data.network.createUploadApi
import com.kimhit.cameo.data.pending.PendingCameoDao
import com.kimhit.cameo.data.pending.PendingCameoEntity
import com.kimhit.cameo.data.pending.PendingCameoRepositoryImpl
import com.kimhit.cameo.data.quota.QuotaRepositoryImpl
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.PendingStep
import com.kimhit.cameo.domain.usecase.PHOTO_MISSING
import com.kimhit.cameo.domain.model.Quota
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test

class CameoRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var cameos: CameoRepositoryImpl
    private lateinit var quota: QuotaRepositoryImpl
    private lateinit var config: DataConfig

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = DataConfig(server.url("/").toString().trimEnd('/'), "test-token")
        val api = createApiService(config)
        cameos = CameoRepositoryImpl(api, createUploadApi(config))
        quota = QuotaRepositoryImpl(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun cameoJson(status: String, extra: String = "", upload: String = "null") =
        """{"cameo":{"id":"u1","clip":{"id":"c1","title":"雨夜重逢","cost":10},"status":"$status",
            "future_field":1 $extra},"upload":$upload}"""

    private fun order(r: DomainResult<CameoOrder>) = (r as DomainResult.Success).value
    private fun failure(r: DomainResult<*>) = (r as DomainResult.Failure).error

    @Test
    fun create_sendsIdempotencyKeyAndBody_andParses201WithUpload() = runTest {
        server.enqueue(
            json(
                cameoJson(
                    "AWAITING_UPLOAD",
                    ""","display":{"title":"等待上傳","message":"已占用 10 點，送出後才扣款"}""",
                    """{"url":"/v1/cameos/u1/selfie","expires_at":"2026-11-07T10:30:00+08:00"}""",
                ),
                201,
            ),
        )
        val o = order(cameos.createCameo("key-abc", "c1"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/cameos", req.path)
        assertEquals("key-abc", req.getHeader("Idempotency-Key"))
        assertEquals("""{"clip_id":"c1"}""", req.body.readUtf8())
        assertEquals(CameoStatus.AWAITING_UPLOAD, o.cameo.status)
        assertEquals("雨夜重逢", o.cameo.clip.title)
        assertEquals("等待上傳", o.cameo.displayTitle)
        assertEquals("已占用 10 點，送出後才扣款", o.cameo.displayMessage)
        assertEquals("2026-11-07T10:30:00+08:00", o.upload?.expiresAt)
        assertEquals("/v1/cameos/u1/selfie", o.upload?.url)
    }

    @Test
    fun create_200WithNullUploadAndExpiredParses() = runTest {
        server.enqueue(json(cameoJson("EXPIRED", ""","display":{"title":"已過期","message":"請重新客串"}"""), 200))
        val o = order(cameos.createCameo("k", "c1"))
        assertEquals(CameoStatus.EXPIRED, o.cameo.status)
        assertNull(o.upload)
    }

    @Test
    fun unknownStatusAndMissingDisplayDoNotCrash() = runTest {
        server.enqueue(json(cameoJson("SOMETHING_NEW")))
        val o = order(cameos.getCameo("u1"))
        assertEquals(CameoStatus.UNKNOWN, o.cameo.status)
        assertNull(o.cameo.displayTitle)
        assertNull(o.cameo.displayMessage)
    }

    @Test
    fun create_429CarriesMessageCodeAndNotRetryable() = runTest {
        server.enqueue(json("""{"code":"DAILY_LIMIT_REACHED","message":"今日客串次數已用完","retryable":false}""", 429))
        val e = failure(cameos.createCameo("k", "c1"))
        assertEquals("今日客串次數已用完", e.message)
        assertEquals("DAILY_LIMIT_REACHED", e.code)
        assertEquals(false, e.retryable)
    }

    @Test
    fun create_disconnectAfterRequestReceived_isRetryable() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val e = failure(cameos.createCameo("k", "c1"))
        assertTrue(e.retryable)
        assertNull(e.message)
        assertEquals("k", server.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test
    fun get404_hasCameoNotFoundCode() = runTest {
        server.enqueue(json("""{"code":"CAMEO_NOT_FOUND","message":"找不到","retryable":false}""", 404))
        val e = failure(cameos.getCameo("nope"))
        assertEquals("CAMEO_NOT_FOUND", e.code)
        assertEquals("/v1/cameos/nope", server.takeRequest().path)
    }

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3, 4, 5)
    private fun photo(): File = File(tmp.newFolder(), "k1.jpg").apply { writeBytes(jpegBytes) }

    @Test
    fun upload_putsToRelativePath_withSameBytes_contentType_andAuthorization() = runTest {
        server.enqueue(json(cameoJson("UPLOADED")))
        val o = order(cameos.uploadSelfie("/v1/cameos/u1/selfie", photo().path))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/v1/cameos/u1/selfie", req.path)
        assertTrue(jpegBytes.contentEquals(req.body.readByteArray()))
        assertEquals("image/jpeg", req.getHeader("Content-Type"))
        assertEquals("Bearer test-token", req.getHeader("Authorization"))
        assertEquals(CameoStatus.UPLOADED, o.cameo.status)
    }

    @Test
    fun clients_uploadDoesNotRetry_mainStillRetries() {
        val main = createMainClient(config)
        val upload = createUploadClient(main)
        assertTrue(main.retryOnConnectionFailure)
        assertFalse(upload.retryOnConnectionFailure)
        assertTrue(main.retryOnConnectionFailure) // 衍生上傳 client 不影響主 client
        assertEquals(60_000, upload.writeTimeoutMillis)
        assertEquals(60_000, upload.readTimeoutMillis)
    }

    @Test
    fun upload_serverDropsConnection_sentOnce_andRetryableFailure() = runTest {
        // 多排一個回應：若 OkHttp 自己重送，第二個請求會被計入
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        val e = failure(cameos.uploadSelfie("/v1/cameos/u1/selfie", photo().path))
        assertEquals(1, server.requestCount)
        assertTrue(e.retryable)
    }

    @Test
    fun upload_missingFile_isPhotoMissing_noRequest() = runTest {
        val e = failure(cameos.uploadSelfie("/v1/cameos/u1/selfie", File(tmp.root, "nope.jpg").path))
        assertEquals(PHOTO_MISSING, e.code)
        assertEquals(false, e.retryable)
        assertEquals(0, server.requestCount)
    }

    // 408 不能被 OkHttp 自動重送整個 body：上傳 client 的 retryOnConnectionFailure 必須是 false。
    @Test
    fun upload_408IsNotAutoRetriedByOkHttp() = runTest {
        server.enqueue(json("""{"code":"UPLOAD_INTERRUPTED","message":"訊息","retryable":true}""", 408))
        server.enqueue(json("""{"code":"UPLOAD_INTERRUPTED","message":"訊息","retryable":true}""", 408))
        val e = failure(cameos.uploadSelfie("/v1/cameos/u1/selfie", photo().path))
        assertEquals("UPLOAD_INTERRUPTED", e.code)
        assertEquals(true, e.retryable)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun upload_errorsCarryCodeAndRetryable() = runTest {
        val cases = listOf(
            Triple(408, "UPLOAD_INTERRUPTED", true),
            Triple(410, "UPLOAD_EXPIRED", false),
            Triple(413, "SELFIE_TOO_LARGE", false),
            Triple(409, "INVALID_STATE", false),
            Triple(400, "INVALID_SELFIE", false),
        )
        val file = photo()
        for ((status, code, retryable) in cases) {
            server.enqueue(json("""{"code":"$code","message":"訊息","retryable":$retryable}""", status))
            val e = failure(cameos.uploadSelfie("/v1/cameos/u1/selfie", file.path))
            assertEquals(code, code, e.code)
            assertEquals(code, retryable, e.retryable)
            assertEquals(code, "訊息", e.message)
        }
    }

    @Test
    fun submit_postsToSubmitPath_noBody_andParses() = runTest {
        server.enqueue(json(cameoJson("QUEUED", ""","display":{"title":"排隊中","message":"完成後會通知你"}""")))
        val o = order(cameos.submitCameo("u1"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/cameos/u1/submit", req.path)
        assertEquals(0L, req.bodySize)
        assertEquals("Bearer test-token", req.getHeader("Authorization"))
        assertEquals(CameoStatus.QUEUED, o.cameo.status)
        assertEquals("排隊中", o.cameo.displayTitle)
    }

    @Test
    fun submit_errorsCarryCodeAndRetryable() = runTest {
        val cases = listOf(
            Triple(410, "UPLOAD_EXPIRED", false),
            Triple(409, "SELFIE_NOT_UPLOADED", false),
            Triple(409, "INVALID_STATE", false),
            Triple(404, "CAMEO_NOT_FOUND", false),
        )
        for ((status, code, retryable) in cases) {
            server.enqueue(json("""{"code":"$code","message":"訊息","retryable":$retryable}""", status))
            val e = failure(cameos.submitCameo("u1"))
            assertEquals(code, code, e.code)
            assertEquals(code, retryable, e.retryable)
        }
    }

    @Test
    fun submit_disconnect_isRetryable() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(failure(cameos.submitCameo("u1")).retryable)
    }

    @Test
    fun quota_parses() = runTest {
        server.enqueue(json("""{"remaining":2,"resets_at":"2026-11-08T00:00:00+08:00","display":"今日還可客串 2 次"}"""))
        assertEquals(
            DomainResult.Success(Quota(2, "2026-11-08T00:00:00+08:00", "今日還可客串 2 次")),
            quota.getQuota(),
        )
        assertEquals("/v1/quota", server.takeRequest().path)
    }

    @Test
    fun cancellationIsRethrownNotConvertedToFailure() = runBlocking {
        server.enqueue(json(cameoJson("AWAITING_UPLOAD")).setBodyDelay(10, TimeUnit.SECONDS))
        val outcome = CompletableDeferred<String>()
        val job = launch(Dispatchers.IO) {
            try {
                cameos.getCameo("u1")
                outcome.complete("returned")
            } catch (e: CancellationException) {
                outcome.complete("cancelled")
                throw e
            }
        }
        server.takeRequest(5, TimeUnit.SECONDS)
        delay(200)
        job.cancel()
        assertEquals("cancelled", withTimeout(5_000) { outcome.await() })
    }
}

private class FakeDao : PendingCameoDao {
    val rows = linkedMapOf<String, PendingCameoEntity>()
    override suspend fun upsert(entity: PendingCameoEntity) {
        rows[entity.clientRequestId] = entity
    }
    override suspend fun get(id: String) = rows[id]
    override suspend fun delete(id: String) {
        rows.remove(id)
    }
    override suspend fun findByCameoId(cameoId: String) = rows.values.firstOrNull { it.cameoId == cameoId }
    override suspend fun deleteByCameoId(cameoId: String): Int {
        val keys = rows.values.filter { it.cameoId == cameoId }.map { it.clientRequestId }
        keys.forEach { rows.remove(it) }
        return keys.size
    }
    // 照 SQL 語意：UPDATE ... WHERE client_request_id = ?，列不存在回 0、不新增
    private fun update(id: String, f: (PendingCameoEntity) -> PendingCameoEntity): Int {
        val row = rows[id] ?: return 0
        rows[id] = f(row)
        return 1
    }
    override suspend fun markCreated(id: String, cameoId: String, now: Long) =
        update(id) { it.copy(step = "UPLOAD", cameoId = cameoId, lastError = null, updatedAt = now) }
    override suspend fun updateError(id: String, message: String?, now: Long) =
        update(id) { it.copy(lastError = message, updatedAt = now) }
    override suspend fun updatePhotoPath(id: String, path: String?, now: Long) =
        update(id) { it.copy(photoPath = path, updatedAt = now) }
    override suspend fun clearPhotoPath(id: String, now: Long): Int {
        if (rows[id]?.photoPath == null) return 0
        return update(id) { it.copy(photoPath = null, updatedAt = now) }
    }
    override suspend fun markUploaded(id: String, now: Long) =
        update(id) { it.copy(step = "SUBMIT", photoPath = null, lastError = null, updatedAt = now) }
    override suspend fun markSubmitted(id: String, now: Long) =
        update(id) { it.copy(step = "TRACK", lastError = null, updatedAt = now) }
    override suspend fun findDraft(clipId: String) =
        rows.values.filter { it.step == "CREATE" && it.clipId == clipId }.maxByOrNull { it.updatedAt }
    override suspend fun list() = rows.values.sortedByDescending { it.updatedAt }
}

class PendingCameoRepositoryTest {
    private val dao = FakeDao()
    private var n = 0
    private var clock = 1000L
    @get:Rule
    val tmp = TemporaryFolder()

    private val photoDir by lazy { File(tmp.root, "selfies") }
    private val repo by lazy { PendingCameoRepositoryImpl(dao, photoDir, newId = { "id-${++n}" }, now = { clock++ }) }

    private fun source(name: String, content: String): File =
        File(tmp.newFolder(), name).apply { writeText(content) }

    @Test
    fun reusesCreateDraftForSameClip() = runTest {
        val a = repo.getOrCreateDraft("c1")
        val b = repo.getOrCreateDraft("c1")
        assertEquals(a.clientRequestId, b.clientRequestId)
        assertEquals(PendingStep.CREATE, a.step)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun differentClipGetsDifferentKey() = runTest {
        val a = repo.getOrCreateDraft("c1")
        val b = repo.getOrCreateDraft("c2")
        assertNotEquals(a.clientRequestId, b.clientRequestId)
    }

    @Test
    fun markCreated_thenNextDraftIsNewKey() = runTest {
        val a = repo.getOrCreateDraft("c1")
        repo.recordError(a.clientRequestId, "x")
        repo.markCreated(a.clientRequestId, "u1")
        val row = dao.rows.getValue(a.clientRequestId)
        assertEquals("UPLOAD", row.step)
        assertEquals("u1", row.cameoId)
        assertNull(row.lastError)
        val b = repo.getOrCreateDraft("c1")
        assertNotEquals(a.clientRequestId, b.clientRequestId)
        assertEquals(2, repo.list().size)
    }

    @Test
    fun recordError_setsAndClearsMessage_andUpdatesTime() = runTest {
        val a = repo.getOrCreateDraft("c1")
        val t0 = dao.rows.getValue(a.clientRequestId).updatedAt
        repo.recordError(a.clientRequestId, "網路錯誤")
        assertEquals("網路錯誤", dao.rows.getValue(a.clientRequestId).lastError)
        assertTrue(dao.rows.getValue(a.clientRequestId).updatedAt > t0)
        repo.recordError(a.clientRequestId, null)
        assertNull(dao.rows.getValue(a.clientRequestId).lastError)
    }

    @Test
    fun missingRowIsNoOp() = runTest {
        repo.markCreated("ghost", "u1")
        repo.recordError("ghost", "x")
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun attachPhoto_movesFileToCanonicalPath_andUpdatesRow() = runTest {
        val d = repo.getOrCreateDraft("c1")
        val src = source("shot.jpg", "AAA")
        assertTrue(repo.attachPhoto(d.clientRequestId, src.path))
        val target = File(photoDir, "${d.clientRequestId}.jpg")
        assertEquals("AAA", target.readText())
        assertFalse(src.exists())
        assertEquals(target.path, dao.rows.getValue(d.clientRequestId).photoPath)
        assertEquals(target.path, repo.getOrCreateDraft("c1").photoPath)
        assertFalse(File(photoDir, "${d.clientRequestId}.jpg.tmp").exists())
    }

    @Test
    fun attachPhoto_missingSource_returnsFalse_keepsOldPhotoAndRow() = runTest {
        val d = repo.getOrCreateDraft("c1")
        assertTrue(repo.attachPhoto(d.clientRequestId, source("a.jpg", "OLD").path))
        val before = dao.rows.getValue(d.clientRequestId)
        assertFalse(repo.attachPhoto(d.clientRequestId, File(tmp.root, "nope.jpg").path))
        assertEquals("OLD", File(photoDir, "${d.clientRequestId}.jpg").readText())
        assertEquals(before, dao.rows.getValue(d.clientRequestId))
    }

    @Test
    fun attachPhoto_overwritesOldPhoto() = runTest {
        val d = repo.getOrCreateDraft("c1")
        assertTrue(repo.attachPhoto(d.clientRequestId, source("a.jpg", "OLD").path))
        assertTrue(repo.attachPhoto(d.clientRequestId, source("b.jpg", "NEW").path))
        assertEquals("NEW", File(photoDir, "${d.clientRequestId}.jpg").readText())
        assertEquals(listOf("${d.clientRequestId}.jpg"), photoDir.list()!!.toList())
    }

    @Test
    fun attachPhoto_samePathAsCanonical_isNoOpTrue() = runTest {
        val d = repo.getOrCreateDraft("c1")
        assertTrue(repo.attachPhoto(d.clientRequestId, source("a.jpg", "X").path))
        val canonical = File(photoDir, "${d.clientRequestId}.jpg")
        assertTrue(repo.attachPhoto(d.clientRequestId, canonical.path))
        assertEquals("X", canonical.readText())
    }

    @Test
    fun attachPhoto_unknownDraft_returnsFalse_leavesSource() = runTest {
        val src = source("a.jpg", "X")
        assertFalse(repo.attachPhoto("ghost", src.path))
        assertTrue(src.exists())
    }

    @Test
    fun delete_removesRowAndPhotoFile() = runTest {
        val d = repo.getOrCreateDraft("c1")
        repo.attachPhoto(d.clientRequestId, source("a.jpg", "X").path)
        val canonical = File(photoDir, "${d.clientRequestId}.jpg")
        assertTrue(canonical.exists())
        repo.delete(d.clientRequestId)
        assertFalse(canonical.exists())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun claimFinishedCameo_onlyFirstCallReturnsTrue_andDeletesRowAndPhoto() = runTest {
        val d = repo.getOrCreateDraft("c1")
        repo.attachPhoto(d.clientRequestId, source("a.jpg", "X").path)
        repo.markCreated(d.clientRequestId, "u1")
        val canonical = File(photoDir, "${d.clientRequestId}.jpg")
        assertTrue(canonical.exists())
        assertTrue(repo.claimFinishedCameo("u1"))
        assertFalse(canonical.exists())
        assertTrue(dao.rows.isEmpty())
        assertFalse(repo.claimFinishedCameo("u1"))
    }

    @Test
    fun markFunctions_onDeletedRow_doNotRecreateIt() = runTest {
        val d = repo.getOrCreateDraft("c1")
        val id = d.clientRequestId
        repo.attachPhoto(id, source("a.jpg", "X").path)
        repo.markCreated(id, "u1")
        assertTrue(repo.claimFinishedCameo("u1"))
        assertTrue(dao.rows.isEmpty())
        repo.markCreated(id, "u1")
        repo.recordError(id, "x")
        repo.markUploaded(id)
        repo.markSubmitted(id)
        repo.deletePhoto(id)
        assertTrue(dao.rows.isEmpty())
        assertNull(repo.get(id))
    }

    @Test
    fun attachPhoto_rowDeletedBeforeWrite_returnsFalse_doesNotRecreate_removesFile() = runTest {
        val d = repo.getOrCreateDraft("c1")
        val id = d.clientRequestId
        // 搬檔之後、寫列之前列被認領刪除：用 DAO 子類模擬
        val racing = object : PendingCameoDao by dao {
            override suspend fun updatePhotoPath(id: String, path: String?, now: Long): Int {
                dao.rows.remove(id)
                return dao.updatePhotoPath(id, path, now)
            }
        }
        val r = PendingCameoRepositoryImpl(racing, photoDir, newId = { "x" }, now = { clock++ })
        assertFalse(r.attachPhoto(id, source("a.jpg", "X").path))
        assertTrue(dao.rows.isEmpty())
        assertFalse(File(photoDir, "$id.jpg").exists())
    }

    /**
     * `get` 回傳列之後立刻刪列（模擬讀到之後被認領）。不經過 `get` 的寫入（條件式 UPDATE）
     * 則在執行前先刪列，兩種路徑都模擬「寫入時列已不在」；其餘委派給 [dao]。
     */
    private fun readThenDeleteRepo() = PendingCameoRepositoryImpl(
        object : PendingCameoDao by dao {
            override suspend fun get(id: String): PendingCameoEntity? {
                val row = dao.get(id)
                dao.rows.remove(id)
                return row
            }
            override suspend fun markCreated(id: String, cameoId: String, now: Long): Int {
                dao.rows.remove(id)
                return dao.markCreated(id, cameoId, now)
            }
            override suspend fun updateError(id: String, message: String?, now: Long): Int {
                dao.rows.remove(id)
                return dao.updateError(id, message, now)
            }
            override suspend fun markUploaded(id: String, now: Long): Int {
                dao.rows.remove(id)
                return dao.markUploaded(id, now)
            }
            override suspend fun markSubmitted(id: String, now: Long): Int {
                dao.rows.remove(id)
                return dao.markSubmitted(id, now)
            }
        },
        photoDir,
        newId = { "x" },
        now = { clock++ },
    )

    @Test
    fun markCreated_rowDeletedAfterRead_doesNotRecreate() = runTest {
        val id = repo.getOrCreateDraft("c1").clientRequestId
        readThenDeleteRepo().markCreated(id, "u1")
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun recordError_rowDeletedAfterRead_doesNotRecreate() = runTest {
        val id = repo.getOrCreateDraft("c1").clientRequestId
        readThenDeleteRepo().recordError(id, "boom")
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun markUploaded_rowDeletedAfterRead_doesNotRecreate() = runTest {
        val id = repo.getOrCreateDraft("c1").clientRequestId
        repo.markCreated(id, "u1")
        readThenDeleteRepo().markUploaded(id)
        assertTrue(dao.rows.isEmpty())
    }

    // markSubmitted 目前不呼叫 get；測試守的是「不得先 get 再 upsert」
    @Test
    fun markSubmitted_rowDeletedAfterRead_doesNotRecreate() = runTest {
        val id = repo.getOrCreateDraft("c1").clientRequestId
        repo.markCreated(id, "u1")
        readThenDeleteRepo().markSubmitted(id)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun claimFinishedCameo_unknownCameo_returnsFalse_keepsOtherRows() = runTest {
        val d = repo.getOrCreateDraft("c1")
        repo.markCreated(d.clientRequestId, "u1")
        assertFalse(repo.claimFinishedCameo("other"))
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun claimFinishedCameo_withoutPhoto_isNotAnError() = runTest {
        val d = repo.getOrCreateDraft("c1")
        repo.markCreated(d.clientRequestId, "u1")
        assertTrue(repo.claimFinishedCameo("u1"))
    }

    @Test
    fun delete_withoutPhotoFile_isNotAnError() = runTest {
        val d = repo.getOrCreateDraft("c1")
        repo.delete(d.clientRequestId)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun list_and_draft_returnNullPhotoWhenFileLost() = runTest {
        val d = repo.getOrCreateDraft("c1")
        repo.attachPhoto(d.clientRequestId, source("a.jpg", "X").path)
        assertEquals(1, repo.list().count { it.photoPath != null })
        File(photoDir, "${d.clientRequestId}.jpg").delete()
        assertNull(repo.list()[0].photoPath)
        assertNull(repo.getOrCreateDraft("c1").photoPath)
        assertEquals(1, dao.rows.size) // 資料列不動
    }

    @Test
    fun markCreated_keepsPhotoPath() = runTest {
        val d = repo.getOrCreateDraft("c1")
        repo.attachPhoto(d.clientRequestId, source("a.jpg", "X").path)
        val before = dao.rows.getValue(d.clientRequestId).photoPath
        assertNotNull(before)
        repo.markCreated(d.clientRequestId, "u1")
        assertEquals(before, dao.rows.getValue(d.clientRequestId).photoPath)
    }

    @Test
    fun recordError_keepsPhotoPath() = runTest {
        val d = repo.getOrCreateDraft("c1")
        repo.attachPhoto(d.clientRequestId, source("a.jpg", "X").path)
        val before = dao.rows.getValue(d.clientRequestId).photoPath
        assertNotNull(before)
        repo.recordError(d.clientRequestId, "err")
        assertEquals(before, dao.rows.getValue(d.clientRequestId).photoPath)
    }

    @Test
    fun deletePhoto_removesFileAndNullsPath_keepsRow() = runTest {
        val d = repo.getOrCreateDraft("c1")
        repo.attachPhoto(d.clientRequestId, source("a.jpg", "X").path)
        repo.markCreated(d.clientRequestId, "u1")
        val canonical = File(photoDir, "${d.clientRequestId}.jpg")
        assertTrue(canonical.exists())
        repo.deletePhoto(d.clientRequestId)
        assertFalse(canonical.exists())
        val row = dao.rows.getValue(d.clientRequestId)
        assertNull(row.photoPath)
        assertEquals("u1", row.cameoId)
        assertEquals("UPLOAD", row.step)
        assertEquals(1, repo.list().size)
    }

    @Test
    fun deletePhoto_twice_secondCallDoesNotTouchUpdatedAt() = runTest {
        val d = repo.getOrCreateDraft("c1")
        repo.attachPhoto(d.clientRequestId, source("a.jpg", "X").path)
        repo.deletePhoto(d.clientRequestId)
        val after1 = dao.rows.getValue(d.clientRequestId)
        assertNull(after1.photoPath)
        repo.deletePhoto(d.clientRequestId)
        val after2 = dao.rows.getValue(d.clientRequestId)
        assertEquals(after1.updatedAt, after2.updatedAt)
    }

    @Test
    fun deletePhoto_withoutFileOrRow_isNotAnError() = runTest {
        val d = repo.getOrCreateDraft("c1")
        repo.deletePhoto(d.clientRequestId)
        assertNull(dao.rows.getValue(d.clientRequestId).photoPath)
        repo.deletePhoto("ghost")
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun get_returnsRowOrNull() = runTest {
        val d = repo.getOrCreateDraft("c1")
        assertEquals(d, repo.get(d.clientRequestId))
        assertNull(repo.get("ghost"))
    }

    @Test
    fun markUploaded_deletesFile_setsSubmit_clearsErrorAndPath() = runTest {
        val d = repo.getOrCreateDraft("c1")
        repo.attachPhoto(d.clientRequestId, source("a.jpg", "X").path)
        repo.markCreated(d.clientRequestId, "u1")
        repo.recordError(d.clientRequestId, "網路錯誤")
        val canonical = File(photoDir, "${d.clientRequestId}.jpg")
        assertTrue(canonical.exists())
        repo.markUploaded(d.clientRequestId)
        assertFalse(canonical.exists())
        val row = dao.rows.getValue(d.clientRequestId)
        assertEquals("SUBMIT", row.step)
        assertNull(row.photoPath)
        assertNull(row.lastError)
        assertEquals("u1", row.cameoId)
        assertNull(repo.get(d.clientRequestId)!!.photoPath)
    }

    @Test
    fun markSubmitted_setsTrack_clearsError() = runTest {
        val d = repo.getOrCreateDraft("c1")
        repo.markCreated(d.clientRequestId, "u1")
        repo.recordError(d.clientRequestId, "x")
        repo.markSubmitted(d.clientRequestId)
        val row = dao.rows.getValue(d.clientRequestId)
        assertEquals("TRACK", row.step)
        assertNull(row.lastError)
        assertEquals("u1", row.cameoId)
    }

    @Test
    fun markUploadedAndSubmitted_missingRow_noOp() = runTest {
        repo.markUploaded("ghost")
        repo.markSubmitted("ghost")
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun unknownStep_dependsOnCameoId() = runTest {
        dao.upsert(PendingCameoEntity("a", "c1", "u1", "FUTURE", null, null, 1))
        dao.upsert(PendingCameoEntity("b", "c1", null, "FUTURE", null, null, 2))
        assertEquals(PendingStep.UPLOAD, repo.get("a")!!.step)
        assertEquals(PendingStep.CREATE, repo.get("b")!!.step)
    }
}
