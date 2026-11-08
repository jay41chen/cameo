package com.kimhit.cameo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kimhit.cameo.domain.model.Cameo
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.PendingCameo
import com.kimhit.cameo.domain.model.Quota
import com.kimhit.cameo.domain.model.UnfinishedCameo
import com.kimhit.cameo.domain.usecase.DeletePendingCameoUseCase
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.usecase.GetCameoPageUseCase
import com.kimhit.cameo.domain.usecase.GetCameoUseCase
import com.kimhit.cameo.submission.SubmissionRunner
import com.kimhit.cameo.tracking.needsTracking
import com.kimhit.cameo.domain.usecase.GetClipsUseCase
import com.kimhit.cameo.domain.usecase.GetPointsUseCase
import com.kimhit.cameo.domain.usecase.GetQuotaUseCase
import com.kimhit.cameo.domain.usecase.GetUnfinishedCameosUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

private const val POLL_DEBOUNCE_MS = 300L
private const val POLL_INTERVAL_MS = 3_000L

/** 輪詢目標：可視（key 在 [visibleKeys]）且還在排隊或生成中的訂單 id。 */
internal fun pollTargets(items: List<Cameo>, visibleKeys: Set<Any>): List<String> =
    items.filter { it.id in visibleKeys && needsTracking(it.status) }.map { it.id }

/** 首頁按下後等權限檢查通過才執行的動作。 */
sealed interface StartAction {
    data object OpenClips : StartAction
    data class Restart(val row: UnfinishedCameo) : StartAction
    data class Continue(val row: UnfinishedCameo) : StartAction
}

enum class StartPrompt {
    NONE,
    REQUEST_NOTIFICATION,
    REQUEST_CAMERA,
    NOTIFICATION_DENIED,
    NOTIFICATION_SETTINGS,
    CAMERA_DENIED,
    CAMERA_SETTINGS,
}

sealed interface StartNavigation {
    data object OpenClips : StartNavigation
    data class OpenCamera(val clipId: String) : StartNavigation
    data class Continue(val pending: PendingCameo) : StartNavigation
}

@HiltViewModel
class MyCameosViewModel @Inject constructor(
    getCameoPage: GetCameoPageUseCase,
    getQuota: GetQuotaUseCase,
    private val getPoints: GetPointsUseCase,
    private val getClips: GetClipsUseCase,
    private val getUnfinished: GetUnfinishedCameosUseCase,
    private val deletePending: DeletePendingCameoUseCase,
    private val getCameo: GetCameoUseCase,
    private val runner: SubmissionRunner,
) : ViewModel() {
    private val _refreshErrors = Channel<String?>(Channel.CONFLATED)

    /**
     * 列表已載入後刷新失敗的一次性事件（帶錯誤訊息），畫面收到後跳提示。已送出的事件在畫面重建時
     * 不會重播，但旋轉會重新觸發 [onResume]，刷新仍失敗時會發新事件。旋轉行為為讀碼推論，尚未實測。[待確認]
     */
    val refreshErrors: Flow<String?> = _refreshErrors.receiveAsFlow()

    private val list = PagedList<Cameo>(
        viewModelScope,
        idOf = { it.id },
        onRefreshFailed = { _refreshErrors.trySend(it) },
    ) { getCameoPage(it) }
    val state: StateFlow<PagedUiState<Cameo>> = list.state

    private val gate = QuotaGate(viewModelScope, getQuota)
    val blockedHint: StateFlow<Quota?> = gate.blockedHint

    private val _unfinished = MutableStateFlow<List<UnfinishedCameo>>(emptyList())
    val unfinished: StateFlow<List<UnfinishedCameo>> = _unfinished

    private val _unfinishedLoaded = MutableStateFlow(false)
    val unfinishedLoaded: StateFlow<Boolean> = _unfinishedLoaded

    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips

    private val _available = MutableStateFlow<Int?>(null)

    /** 首頁右上的可用點數；還沒取得時為 null，取得失敗保留舊值。 */
    val available: StateFlow<Int?> = _available

    private var pointsJob: Job? = null
    private var unfinishedJob: Job? = null
    private var clipsJob: Job? = null

    init {
        loadFirstPage()
    }

    fun loadFirstPage() = list.loadFirstPage()
    fun refresh() = list.refresh()
    fun loadMore() = list.loadMore()

    /**
     * 畫面回到前景時呼叫：列表第一次載入失敗（[PagePhase.Error]）就重新載入，否則靜默刷新；
     * 另刷新待處理區、額度、點數，片段名稱還沒有時補查。
     */
    fun onResume() {
        refreshSections()
        gate.refresh()
        loadClipsIfEmpty()
        refreshPoints()
    }

    private fun refreshSections() {
        if (state.value.phase == PagePhase.Error) loadFirstPage() else refresh()
        refreshUnfinished()
    }

    /**
     * 提交結束（成功或失敗）時刷新已送出區和待處理區，方式同回前景。由畫面在 RESUMED 時啟動、離開就取消；
     * 只刷這兩區，依規格不刷點數和額度。
     */
    suspend fun watchSubmissions() {
        runner.finished.collect { refreshSections() }
    }

    /**
     * 可視輪詢迴圈，由畫面的 repeatOnLifecycle 啟動與取消：畫面離開 RESUMED 就停止，回來重新開始。
     * 可視範圍改變後停 0.3 秒才查（捲動中會一直重新等），之後每 3 秒查一次。每輪重新算目標，沒有目標就不發請求。
     */
    suspend fun pollVisible(visibleKeys: Flow<Set<Any>>) {
        visibleKeys.distinctUntilChanged().collectLatest { keys ->
            delay(POLL_DEBOUNCE_MS)
            while (true) {
                pollOnce(keys)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 同一輪的目標併發查詢；一筆卡住不影響其他筆更新，但這輪結束前不開始下一個間隔。 */
    private suspend fun pollOnce(keys: Set<Any>) {
        val targets = pollTargets(state.value.items, keys)
        if (targets.isEmpty()) return
        coroutineScope { targets.forEach { id -> launch { pollOne(id) } } }
    }

    /** 失敗不顯示錯誤、不寫狀態，下一輪再查；變成 FAILED 時立刻刷新點數。 */
    private suspend fun pollOne(id: String) {
        val r = getCameo(id)
        if (r !is DomainResult.Success) return
        val before = state.value.items.firstOrNull { it.id == id }?.status
        list.updateItem(r.value.cameo)
        if (before != CameoStatus.FAILED && r.value.cameo.status == CameoStatus.FAILED) refreshPoints()
    }

    private fun refreshPoints() {
        pointsJob?.cancel()
        pointsJob = viewModelScope.launch {
            val r = getPoints()
            if (r is DomainResult.Success) _available.value = r.value.available
        }
    }

    private fun refreshUnfinished() {
        unfinishedJob?.cancel()
        unfinishedJob = viewModelScope.launch {
            _unfinished.value = getUnfinished()
            _unfinishedLoaded.value = true
        }
    }

    /** 片段名稱只用來顯示待處理列；失敗保持空清單、不顯示錯誤，下次回前景再查。 */
    private fun loadClipsIfEmpty() {
        if (_clips.value.isNotEmpty()) return
        if (clipsJob?.isActive == true) return
        clipsJob = viewModelScope.launch {
            val r = getClips()
            if (r is DomainResult.Success) _clips.value = r.value
        }
    }

    fun dismissHint() = gate.dismissHint()

    private val _startPrompt = MutableStateFlow(StartPrompt.NONE)

    /** 開始客串前的權限提示；`REQUEST_*` 由畫面啟動系統對話框，其餘為 App 自己的說明對話框。 */
    val startPrompt: StateFlow<StartPrompt> = _startPrompt

    private val _startNavigation = Channel<StartNavigation>(Channel.CONFLATED)

    /** 檢查通過後的一次性導頁事件。 */
    val startNavigation: Flow<StartNavigation> = _startNavigation.receiveAsFlow()

    private var pendingAction: StartAction? = null
    private var requested: StartPermission? = null

    fun onStartClick(permission: StartPermission) = begin(StartAction.OpenClips, permission)

    /** 已建單的繼續不檢查；尚未建單的繼續走和 FAB 一樣的檢查。 */
    fun onContinueClick(row: UnfinishedCameo, permission: StartPermission) {
        if (row.pending.cameoId != null) {
            _startNavigation.trySend(StartNavigation.Continue(row.pending))
            return
        }
        begin(StartAction.Continue(row), permission)
    }

    /** 重新客串：先看額度（用完只顯示額度提示），再查權限；兩者都通過才刪草稿。 */
    fun onRestartClick(row: UnfinishedCameo, permission: StartPermission) {
        if (!gate.tryStart()) return
        begin(StartAction.Restart(row), permission)
    }

    private fun begin(action: StartAction, permission: StartPermission) {
        if (pendingAction != null) return
        pendingAction = action
        apply(permission)
    }

    private fun apply(permission: StartPermission) {
        when (permission) {
            StartPermission.PROCEED -> complete()
            StartPermission.REQUEST_NOTIFICATION -> {
                requested = permission
                _startPrompt.value = StartPrompt.REQUEST_NOTIFICATION
            }
            StartPermission.REQUEST_CAMERA -> {
                requested = permission
                _startPrompt.value = StartPrompt.REQUEST_CAMERA
            }
            StartPermission.NOTIFICATION_SETTINGS -> stop(StartPrompt.NOTIFICATION_SETTINGS)
            StartPermission.CAMERA_SETTINGS -> stop(StartPrompt.CAMERA_SETTINGS)
        }
    }

    /** 中止目前動作並顯示說明；從設定回來要使用者再按一次。 */
    private fun stop(prompt: StartPrompt) {
        pendingAction = null
        requested = null
        _startPrompt.value = prompt
    }

    private fun complete() {
        val action = pendingAction
        pendingAction = null
        requested = null
        when (action) {
            null -> Unit
            StartAction.OpenClips -> _startNavigation.trySend(StartNavigation.OpenClips)
            is StartAction.Continue -> _startNavigation.trySend(StartNavigation.Continue(action.row.pending))
            is StartAction.Restart -> {
                // 刪草稿只發生在這裡：額度與權限都通過之後
                viewModelScope.launch {
                    deletePending(action.row.pending.clientRequestId)
                    refreshUnfinished()
                }
                _startNavigation.trySend(StartNavigation.OpenCamera(action.row.pending.clipId))
            }
        }
    }

    /** 畫面已啟動系統權限對話框：清掉 `REQUEST_*`，待處理動作保留到結果回來。 */
    fun onPermissionRequestLaunched() {
        if (_startPrompt.value == StartPrompt.REQUEST_NOTIFICATION || _startPrompt.value == StartPrompt.REQUEST_CAMERA) {
            _startPrompt.value = StartPrompt.NONE
        }
    }

    /**
     * 系統權限對話框結果。[permission] 是結果回來後重新檢查的結果。
     * 程序重建後待處理動作已不在，直接忽略。
     */
    fun onPermissionResult(granted: Boolean, permission: StartPermission) {
        if (pendingAction == null) return
        val asked = requested
        if (!granted) {
            pendingAction = null
            requested = null
            _startPrompt.value = when (asked) {
                StartPermission.REQUEST_NOTIFICATION ->
                    if (permission == StartPermission.NOTIFICATION_SETTINGS) StartPrompt.NOTIFICATION_SETTINGS
                    else StartPrompt.NOTIFICATION_DENIED
                StartPermission.REQUEST_CAMERA ->
                    if (permission == StartPermission.CAMERA_SETTINGS) StartPrompt.CAMERA_SETTINGS
                    else StartPrompt.CAMERA_DENIED
                else -> _startPrompt.value
            }
            return
        }
        if (permission == asked) {
            // 已同意但系統仍判定要再問同一項：視同要去設定，避免循環
            stop(
                if (asked == StartPermission.REQUEST_NOTIFICATION) StartPrompt.NOTIFICATION_SETTINGS
                else StartPrompt.CAMERA_SETTINGS,
            )
            return
        }
        apply(permission)
    }

    fun dismissStartPrompt() {
        _startPrompt.value = StartPrompt.NONE
    }

    fun remove(row: UnfinishedCameo) {
        viewModelScope.launch {
            deletePending(row.pending.clientRequestId)
            refreshUnfinished()
        }
    }
}
