package com.kimhit.cameo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import android.os.SystemClock
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kimhit.cameo.domain.model.LedgerEntry
import com.kimhit.cameo.domain.model.LedgerType
import com.kimhit.cameo.domain.model.PendingCameo
import com.kimhit.cameo.domain.model.UnfinishedCameo
import com.kimhit.cameo.domain.model.Cameo
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.Quota
import com.kimhit.cameo.domain.model.Clip

internal const val GENERIC_ERROR = "發生錯誤，請稍後再試"
internal const val EXPIRED_TITLE = "已過期"
internal const val EXPIRED_MESSAGE = "上傳期限已過，點數沒有扣款，請重新客串。"

/** 每頁各用一個節流：只擋同一頁的連點，前一頁的點擊不會吃掉這一頁的點擊。 */
@Composable
private fun rememberNavClickThrottle(): ClickThrottle =
    remember { ClickThrottle(NAV_CLICK_INTERVAL_MS, SystemClock::elapsedRealtime) }

@Composable
fun CameoRoot(openMyCameosRequest: Int = 0) {
    // 單一導覽堆疊：首頁是我的客串，選片段、點數與流程頁都從首頁進入。
    val rootNav = rememberNavController()
    NavHost(rootNav, startDestination = ROUTE_MY_CAMEOS) {
        composable(ROUTE_MY_CAMEOS) {
            MyCameosScreen(
                refreshRequest = openMyCameosRequest,
                onStartCamera = { rootNav.navigateSingleTop(cameraRoute(it)) },
                onContinue = { rootNav.navigateSingleTop(continueRoute(it)) },
                onStartClick = { rootNav.navigateSingleTop(ROUTE_CLIPS) },
                onOpenPoints = { rootNav.navigateSingleTop(ROUTE_POINTS) },
            )
        }
        composable(ROUTE_CLIPS) {
            FlowScaffold {
                ClipsScreen(
                    onStartCamera = { rootNav.navigateSingleTop(cameraRoute(it)) },
                )
            }
        }
        composable(ROUTE_POINTS) { FlowScaffold { PointsScreen() } }
        composable(
            ROUTE_CAMERA,
            arguments = listOf(
                navArgument("clipId") { type = NavType.StringType },
                navArgument("request") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val clipId = checkNotNull(entry.arguments?.getString("clipId"))
            val request = entry.arguments?.getString("request")
            // 離開相機頁：確認頁返回回到進入流程的頁面，進度頁返回回首頁
            FlowScaffold {
                CameraScreen(
                    onUsePhoto = {
                        val route = if (request != null) cameoRoute(request, it) else confirmRoute(clipId, it)
                        rootNav.navigateSingleTop(route, ROUTE_CAMERA)
                    },
                )
            }
        }
        composable(
            ROUTE_CONFIRM,
            arguments = listOf(
                navArgument("clipId") { type = NavType.StringType },
                navArgument("photo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val clipId = checkNotNull(entry.arguments?.getString("clipId"))
            FlowScaffold {
                ConfirmScreen(
                    onBack = { rootNav.navigateUp() },
                    onRetake = { rootNav.navigateSingleTop(cameraRoute(clipId), ROUTE_CONFIRM) },
                    onBackHome = { rootNav.popBackStack(ROUTE_MY_CAMEOS, inclusive = false) },
                    onCreated = { rootNav.navigateToProgress(cameoRoute(it)) },
                )
            }
        }
        composable(
            ROUTE_CAMEO,
            arguments = listOf(
                navArgument("clientRequestId") { type = NavType.StringType },
                navArgument("photo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            FlowScaffold {
                CameoProgressScreen(
                    onBack = { rootNav.navigateUp() },
                    onLeave = { rootNav.popBackStack(ROUTE_MY_CAMEOS, inclusive = false) },
                    onRetake = { clipId, key -> rootNav.navigateSingleTop(cameraRoute(clipId, key), ROUTE_CAMEO) },
                    onNotCreated = { clipId -> rootNav.navigateSingleTop(confirmRoute(clipId), ROUTE_CAMEO) },
                )
            }
        }
    }
    // 必須在 NavHost 之後 [待確認：冷啟動時 graph 要先設定好才能導覽，放前面是否出錯未驗證]
    // 點通知：流程頁一律結束，回首頁
    LaunchedEffect(openMyCameosRequest) {
        if (openMyCameosRequest > 0) rootNav.popBackStack(ROUTE_MY_CAMEOS, inclusive = false)
    }
}

/** 選片段、點數與流程頁（相機、確認、進度）的外框：保留系統列留白。 */
@Composable
private fun FlowScaffold(content: @Composable () -> Unit) {
    Scaffold { padding -> Box(Modifier.padding(padding)) { content() } }
}

/** launchSingleTop：快速連點不會推兩層頁面。[popUpToRoute] 不為 null 時連同該頁一起彈出。 */
private fun NavController.navigateSingleTop(route: String, popUpToRoute: String? = null) {
    navigate(route) {
        launchSingleTop = true
        if (popUpToRoute != null) popUpTo(popUpToRoute) { inclusive = true }
    }
}

/** 建單後進進度頁：彈回首頁為止（首頁保留），選片段和確認頁移出堆疊，進度頁按返回回首頁。 */
private fun NavController.navigateToProgress(route: String) {
    navigate(route) {
        popUpTo(ROUTE_MY_CAMEOS)
    }
}

/** 畫面回到前景（ON_RESUME）時呼叫 [action]；涵蓋首次進入、回前景、從其他畫面返回。 */
@Composable
internal fun OnResume(action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(action)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) latest()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
internal fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
internal fun ErrorBox(message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(message ?: GENERIC_ERROR)
        Button(onClick = onRetry) { Text("重試") }
    }
}

@Composable
fun ClipsScreen(
    onStartCamera: (String) -> Unit,
    viewModel: ClipsViewModel = hiltViewModel(),
) {
    val throttle = rememberNavClickThrottle()
    val state by viewModel.state.collectAsState()
    val quota by viewModel.quota.collectAsState()
    val hint by viewModel.blockedHint.collectAsState()
    OnResume(viewModel::onResume)
    when (val s = state) {
        Load.Loading -> LoadingBox()
        is Load.Error -> ErrorBox(s.message, viewModel::load, Modifier.fillMaxSize())
        is Load.Success -> LazyColumn(Modifier.fillMaxSize()) {
            item { QuotaHeader(quota, viewModel::refreshQuota) }
            items(s.value, key = { it.id }) { clip ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            throttle.guarded { if (viewModel.tryStart()) onStartCamera(clip.id) }
                        }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(clip.title, style = MaterialTheme.typography.titleMedium)
                    Text("${clip.cost} 點")
                }
                HorizontalDivider()
            }
        }
    }
    QuotaBlockedDialog(hint, viewModel::dismissHint)
}

@Composable
private fun QuotaBlockedDialog(hint: Quota?, onDismiss: () -> Unit) {
    hint?.let { q ->
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text("${q.display}，${formatTime(q.resetsAt)} 重置") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        )
    }
}

@Composable
private fun QuotaHeader(quota: Load<Quota>, onRetry: () -> Unit) {
    when (quota) {
        Load.Loading -> Unit
        is Load.Error -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("無法取得今日額度")
            TextButton(onClick = onRetry) { Text("重試") }
        }
        is Load.Success -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(quota.value.display, style = MaterialTheme.typography.titleMedium)
            if (quota.value.remaining == 0) {
                Text("${formatTime(quota.value.resetsAt)} 重置", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    HorizontalDivider()
}

/** 尚未建立訂單（cameoId == null）的未完成列文字。 */
internal fun pendingNoOrderText(p: PendingCameo): String = when {
    p.lastError != null -> p.lastError!!
    p.photoPath == null -> NEED_PHOTO_TEXT
    else -> "尚未確認是否建立成功"
}

@Composable
private fun UnfinishedRow(
    row: UnfinishedCameo,
    clips: List<Clip>,
    onContinue: () -> Unit,
    onRestart: () -> Unit,
    onRemove: () -> Unit,
) {
    val order = row.pending.cameoId?.let { row.order }
    val clipName = row.order?.cameo?.clip?.title
        ?: clips.firstOrNull { it.id == row.pending.clipId }?.title
        ?: "（片段）"
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(clipName, style = MaterialTheme.typography.titleSmall)
        when {
            row.pending.cameoId == null -> {
                Text(pendingNoOrderText(row.pending))
                // 不經 tryStart：同 key 重送，後端會回原訂單
                Row {
                    TextButton(onClick = onContinue) { Text("繼續") }
                    TextButton(onClick = onRemove) { Text("移除") }
                }
            }
            order == null -> Text("暫時無法取得狀態")
            else -> {
                val expired = order.cameo.status == CameoStatus.EXPIRED
                Text(order.cameo.displayTitle?.takeIf { it.isNotBlank() }
                        ?: if (expired) EXPIRED_TITLE else "處理中")
                val message = order.cameo.displayMessage?.takeIf { it.isNotBlank() }
                    ?: if (expired) EXPIRED_MESSAGE else null
                message?.let { Text(it) }
                order.upload?.let { Text("上傳期限：${formatTime(it.expiresAt)}") }
                if (canContinueOrder(order)) {
                    // 不經 tryStart：訂單已建立，只是接續上傳／送出
                    TextButton(onClick = onContinue) { Text("繼續") }
                }
                if (expired) {
                    Row {
                        TextButton(onClick = onRestart) { Text("重新客串") }
                        TextButton(onClick = onRemove) { Text("移除") }
                    }
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
fun PointsScreen(viewModel: PointsViewModel = hiltViewModel()) {
    val points by viewModel.points.collectAsState()
    val ledger by viewModel.ledger.collectAsState()
    OnResume {
        viewModel.refreshPoints()
        viewModel.refreshLedger()
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            when (val p = points) {
                Load.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
                is Load.Error -> ErrorBox(p.message, viewModel::loadPoints, Modifier.fillMaxWidth())
                is Load.Success -> Column(Modifier.padding(16.dp)) {
                    Text("可用點數：${p.value.available}", style = MaterialTheme.typography.titleLarge)
                    Text("占用點數：${p.value.held}", style = MaterialTheme.typography.titleMedium)
                }
            }
            HorizontalDivider()
            Text("點數明細", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }
        when (ledger.phase) {
            PagePhase.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
            }
            PagePhase.Error -> item {
                ErrorBox(ledger.errorMessage, viewModel::loadFirstPage, Modifier.fillMaxWidth())
            }
            else -> {
                if (ledger.items.isEmpty()) {
                    item { Text("目前沒有點數明細", Modifier.padding(16.dp)) }
                }
                items(ledger.items, key = { it.id }) { LedgerRow(it) }
                pagedFooter(ledger, viewModel::loadMore)
            }
        }
    }
}

@Composable
private fun LedgerRow(entry: LedgerEntry) {
    val label = when (entry.type) {
        LedgerType.CHARGE -> "扣點"
        LedgerType.REFUND -> "退點"
        LedgerType.UNKNOWN -> "其他"
    }
    val sign = if (entry.type == LedgerType.REFUND) "+" else "-"
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(entry.clipTitle ?: "（無片段名稱）")
                Text(
                    "$label・${formatTime(entry.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("$sign${entry.amount}")
        }
        HorizontalDivider()
    }
}

/** 列表底部：載入更多的轉圈、失敗重試，或捲到底時自動載入下一頁。 */
private fun LazyListScope.pagedFooter(state: PagedUiState<*>, loadMore: () -> Unit) {
    when (state.phase) {
        PagePhase.LoadingMore -> item {
            Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator() }
        }
        PagePhase.LoadMoreError -> item {
            ErrorBox(state.errorMessage, loadMore, Modifier.fillMaxWidth())
        }
        else -> if (state.nextCursor != null) item {
            // 捲到清單底部時自動載入下一頁
            LaunchedEffect(state.nextCursor) { loadMore() }
        }
    }
}

private const val PENDING_HEADER_KEY = "pending_header"
private const val SUBMITTED_HEADER_KEY = "submitted_header"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyCameosScreen(
    refreshRequest: Int = 0,
    onStartCamera: (String) -> Unit,
    onContinue: (PendingCameo) -> Unit,
    onStartClick: () -> Unit,
    onOpenPoints: () -> Unit,
    viewModel: MyCameosViewModel = hiltViewModel(),
) {
    val throttle = rememberNavClickThrottle()
    val state by viewModel.state.collectAsState()
    val unfinished by viewModel.unfinished.collectAsState()
    val unfinishedLoaded by viewModel.unfinishedLoaded.collectAsState()
    val clips by viewModel.clips.collectAsState()
    val hint by viewModel.blockedHint.collectAsState()
    val available by viewModel.available.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showBackToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val firstId = state.items.firstOrNull()?.id
    val listTop = ListTop(
        topKey = if (unfinished.isNotEmpty()) PENDING_HEADER_KEY else firstId,
        firstId = firstId,
        pendingCount = unfinished.size,
    )
    var previousTop by remember { mutableStateOf<ListTop?>(null) }
    LaunchedEffect(listTop) {
        val shouldScroll = shouldScrollToTop(
            previousTop,
            listTop,
            listState.layoutInfo.visibleItemsInfo.map { it.key },
        )
        // 先更新再捲動：animateScrollToItem 可能因 listTop 再變而被取消
        previousTop = listTop
        if (shouldScroll) listState.animateScrollToItem(0)
    }
    val context = LocalContext.current
    val startPrompt by viewModel.startPrompt.collectAsState()
    fun startPermission() = currentStartPermission(context)
    // afterDenial：同意後用 false，下一項權限才會請求；拒絕後用 true，才能分辨系統還能不能問
    val onPermissionResult = { granted: Boolean ->
        viewModel.onPermissionResult(granted, currentStartPermission(context, afterDenial = !granted))
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onPermissionResult)
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onPermissionResult)
    LaunchedEffect(startPrompt) {
        when (startPrompt) {
            StartPrompt.REQUEST_NOTIFICATION -> {
                viewModel.onPermissionRequestLaunched()
                notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            StartPrompt.REQUEST_CAMERA -> {
                viewModel.onPermissionRequestLaunched()
                cameraLauncher.launch(android.Manifest.permission.CAMERA)
            }
            else -> Unit
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.startNavigation.collect { nav ->
            when (nav) {
                StartNavigation.OpenClips -> onStartClick()
                is StartNavigation.OpenCamera -> onStartCamera(nav.clipId)
                is StartNavigation.Continue -> onContinue(nav.pending)
            }
        }
    }
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.refreshErrors.collectLatest { message ->
            val result = snackbarHost.showSnackbar(
                message = message ?: GENERIC_ERROR,
                actionLabel = "重試",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.refresh()
        }
    }
    OnResume(viewModel::onResume)
    // [待確認] 已在首頁時點通知可能不觸發 OnResume，用請求計數補一次刷新（未驗證）
    LaunchedEffect(refreshRequest) { if (refreshRequest > 0) viewModel.onResume() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("我的客串") },
                actions = { TextButton(onClick = { throttle.guarded { onOpenPoints() } }) { Text(pointsLabel(available)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { throttle.guarded { viewModel.onStartClick(startPermission()) } },
            ) { Text("＋客串") }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = 80.dp),
            ) {
                if (unfinished.isNotEmpty()) {
                    item(key = PENDING_HEADER_KEY) {
                        Text(
                            "待處理",
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(unfinished, key = { it.pending.clientRequestId }) { row ->
                        UnfinishedRow(
                            row = row,
                            clips = clips,
                            onContinue = { throttle.guarded { viewModel.onContinueClick(row, startPermission()) } },
                            onRestart = { throttle.guarded { viewModel.onRestartClick(row, startPermission()) } },
                            onRemove = { viewModel.remove(row) },
                        )
                    }
                }
                when (
                    submittedSection(
                        state.phase,
                        state.items.isNotEmpty(),
                        unfinishedLoaded,
                        unfinished.isNotEmpty(),
                    )
                ) {
                    SubmittedSection.Loading -> item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    SubmittedSection.Error -> item {
                        ErrorBox(state.errorMessage, viewModel::loadFirstPage, Modifier.fillMaxWidth())
                    }
                    SubmittedSection.AllEmpty -> item { Text("還沒有客串", Modifier.padding(16.dp)) }
                    SubmittedSection.Content -> {
                        if (unfinished.isNotEmpty() && state.items.isNotEmpty()) {
                            item(key = SUBMITTED_HEADER_KEY) {
                                Text(
                                    "已送出",
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                        items(state.items, key = { it.id }) { MyCameoRow(it) }
                        pagedFooter(state, viewModel::loadMore)
                    }
                }
            }
            if (showBackToTop) {
                Button(
                    onClick = { scope.launch { listState.scrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                ) { Text("回到頂端") }
            }
        }
    }
    QuotaBlockedDialog(hint, viewModel::dismissHint)
    StartPermissionDialog(
        prompt = startPrompt,
        onDismiss = viewModel::dismissStartPrompt,
        onOpenNotificationSettings = { openNotificationSettings(context) },
        onOpenAppSettings = { openAppSettings(context) },
    )
}

@Composable
private fun MyCameoRow(cameo: Cameo) {
    Column {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(cameo.clip.title, style = MaterialTheme.typography.titleMedium)
            Text(cameo.displayTitle ?: "狀態未知")
            cameo.displayMessage?.let { Text(it) }
            cameo.createdAt?.let { Text(formatTime(it), style = MaterialTheme.typography.bodySmall) }
        }
        HorizontalDivider()
    }
}
