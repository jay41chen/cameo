package com.kimhit.cameo.domain.usecase

import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainError
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.LedgerPage
import com.kimhit.cameo.domain.model.Points
import com.kimhit.cameo.domain.repository.ClipRepository
import com.kimhit.cameo.domain.repository.PointsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeClipRepository(var result: DomainResult<List<Clip>>) : ClipRepository {
    var calls = 0
    override suspend fun getClips(): DomainResult<List<Clip>> {
        calls++
        return result
    }
}

private class FakePointsRepository(
    var points: DomainResult<Points>,
    var ledger: DomainResult<LedgerPage>,
) : PointsRepository {
    val cursors = mutableListOf<String?>()
    override suspend fun getPoints() = points
    override suspend fun getLedger(cursor: String?): DomainResult<LedgerPage> {
        cursors += cursor
        return ledger
    }
}

class UseCasesTest {
    private val failure = DomainResult.Failure(DomainError("後端訊息", retryable = true))

    @Test
    fun getClips_returnsRepositoryResult() = runTest {
        val clips = listOf(Clip("c1", "雨夜重逢", 10))
        val repo = FakeClipRepository(DomainResult.Success(clips))
        assertEquals(DomainResult.Success(clips), GetClipsUseCase(repo)())
        assertEquals(1, repo.calls)
    }

    @Test
    fun getClips_passesFailureThrough() = runTest {
        val repo = FakeClipRepository(failure)
        assertEquals(failure, GetClipsUseCase(repo)())
    }

    @Test
    fun getPoints_returnsRepositoryResult() = runTest {
        val repo = FakePointsRepository(DomainResult.Success(Points(100, 0)), failure)
        assertEquals(DomainResult.Success(Points(100, 0)), GetPointsUseCase(repo)())
    }

    @Test
    fun getLedgerPage_passesCursorAndDefaultsToFirstPage() = runTest {
        val page = LedgerPage(emptyList(), "next")
        val repo = FakePointsRepository(DomainResult.Failure(DomainError(null, false)), DomainResult.Success(page))
        val useCase = GetLedgerPageUseCase(repo)
        assertEquals(DomainResult.Success(page), useCase())
        useCase("abc")
        assertEquals(listOf(null, "abc"), repo.cursors)
    }

    @Test
    fun getLedgerPage_passesFailureThrough() = runTest {
        val repo = FakePointsRepository(DomainResult.Success(Points(1, 0)), failure)
        assertEquals(failure, GetLedgerPageUseCase(repo)("x"))
    }
}
