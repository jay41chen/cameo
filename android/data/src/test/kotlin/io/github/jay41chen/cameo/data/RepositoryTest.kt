package io.github.jay41chen.cameo.data

import io.github.jay41chen.cameo.data.clips.ClipRepositoryImpl
import io.github.jay41chen.cameo.data.network.DataConfig
import io.github.jay41chen.cameo.data.network.createApiService
import io.github.jay41chen.cameo.data.points.PointsRepositoryImpl
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.LedgerType
import io.github.jay41chen.cameo.domain.model.Points
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var clips: ClipRepositoryImpl
    private lateinit var points: PointsRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = createApiService(DataConfig(server.url("/").toString().trimEnd('/'), "test-token"))
        clips = ClipRepositoryImpl(api)
        points = PointsRepositoryImpl(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun failure(r: DomainResult<*>) = (r as DomainResult.Failure).error

    @Test
    fun clips_parsesSuccessAndSendsAuthHeader() = runTest {
        server.enqueue(json("""{"items":[{"id":"c1","title":"雨夜重逢","cost":10}]}"""))
        val result = clips.getClips()
        assertEquals(DomainResult.Success(listOf(Clip("c1", "雨夜重逢", 10))), result)
        val request = server.takeRequest()
        assertEquals("/v1/clips", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test
    fun clips_ignoresUnknownFields() = runTest {
        server.enqueue(json("""{"extra":1,"items":[{"id":"c1","title":"t","cost":5,"new_field":{"a":1}}]}"""))
        assertEquals(DomainResult.Success(listOf(Clip("c1", "t", 5))), clips.getClips())
    }

    @Test
    fun points_parsesSuccess() = runTest {
        server.enqueue(json("""{"available":100,"held":0}"""))
        assertEquals(DomainResult.Success(Points(100, 0)), points.getPoints())
    }

    @Test
    fun unauthorized_401_carriesBackendMessage() = runTest {
        server.enqueue(json("""{"code":"UNAUTHORIZED","message":"請重新登入","retryable":false}""", 401))
        val error = failure(points.getPoints())
        assertEquals("請重新登入", error.message)
        assertEquals(false, error.retryable)
    }

    @Test
    fun serverError_500_carriesRetryable() = runTest {
        server.enqueue(json("""{"code":"INTERNAL","message":"系統忙碌","retryable":true}""", 500))
        val error = failure(clips.getClips())
        assertEquals("系統忙碌", error.message)
        assertTrue(error.retryable)
    }

    @Test
    fun nonJsonError_hasNoMessage_and5xxIsRetryable() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"))
        val error = failure(clips.getClips())
        assertNull(error.message)
        assertTrue(error.retryable)
    }

    @Test
    fun nonJsonError_4xxIsNotRetryable() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val error = failure(clips.getClips())
        assertNull(error.message)
        assertEquals(false, error.retryable)
    }

    @Test
    fun malformedSuccessBody_isFailure() = runTest {
        server.enqueue(json("not json"))
        assertNotNull(failure(clips.getClips()))
    }

    @Test
    fun networkDisconnect_isRetryableFailureWithoutMessage() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val error = failure(points.getPoints())
        assertNull(error.message)
        assertTrue(error.retryable)
    }

    @Test
    fun ledger_firstPageHasNoCursorParam_andParsesFields() = runTest {
        server.enqueue(
            json(
                """{"items":[{"id":"l1","type":"CHARGE","amount":10,"cameo_id":null,"clip_title":"雨夜重逢","created_at":"2026-11-07T10:01:00+08:00","x":1},
                   {"id":"l2","type":"REFUND","amount":10,"cameo_id":"u1","clip_title":"屋頂告白","created_at":"2026-11-07T10:02:00+08:00"},
                   {"id":"l3","type":"SOMETHING_NEW","amount":1,"cameo_id":null,"clip_title":null,"created_at":"2026-11-07T10:03:00+08:00"}],
                   "next_cursor":"abc=="}""",
            ),
        )
        val page = (points.getLedger(null) as DomainResult.Success).value
        assertEquals("/v1/points/ledger", server.takeRequest().path)
        assertEquals("abc==", page.nextCursor)
        assertEquals(listOf(LedgerType.CHARGE, LedgerType.REFUND, LedgerType.UNKNOWN), page.items.map { it.type })
        assertNull(page.items[0].cameoId)
        assertEquals("u1", page.items[1].cameoId)
        assertNull(page.items[2].clipTitle)
    }

    @Test
    fun ledger_passesCursorAndEncodesIt() = runTest {
        server.enqueue(json("""{"items":[],"next_cursor":null}"""))
        val page = (points.getLedger("a+b/c=") as DomainResult.Success).value
        assertNull(page.nextCursor)
        assertEquals("/v1/points/ledger?cursor=a%2Bb%2Fc%3D", server.takeRequest().path)
    }
}
