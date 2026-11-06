package com.kimhit.cameo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import android.os.SystemClock
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kimhit.cameo.domain.model.LedgerEntry
import com.kimhit.cameo.domain.model.LedgerType
import com.kimhit.cameo.domain.model.UnfinishedCameo
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.Quota
import com.kimhit.cameo.domain.model.Clip

private const val ROUTE_CLIPS = "clips"
private const val ROUTE_POINTS = "points"
private const val ROUTE_CONFIRM = "confirm/{clipId}"
internal const val GENERIC_ERROR = "發生錯誤，請稍後再試"
internal const val EXPIRED_TITLE = "已過期"
internal const val EXPIRED_MESSAGE = "上傳期限已過，點數沒有扣款，請重新客串。"

@Composable
fun CameoRoot() {
    val nav = rememberNavController()
    val throttle = remember { ClickThrottle(NAV_CLICK_INTERVAL_MS, SystemClock::elapsedRealtime) }
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val showBottomBar = current == ROUTE_CLIPS || current == ROUTE_POINTS
    Scaffold(
        bottomBar = {
            if (showBottomBar) NavigationBar {
                listOf(ROUTE_CLIPS to "片段清單", ROUTE_POINTS to "點數").forEach { (route, label) ->
                    NavigationBarItem(
                        selected = current == route,
                        onClick = {
                            nav.navigate(route) {
                                popUpTo(ROUTE_CLIPS) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {},
                        label = { Text(label) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = ROUTE_CLIPS, modifier = Modifier.padding(padding)) {
            composable(ROUTE_CLIPS) { ClipsScreen(throttle = throttle, onStartConfirm = { nav.navigateToConfirm(it) }) }
            composable(ROUTE_POINTS) { PointsScreen() }
            composable(
                ROUTE_CONFIRM,
                arguments = listOf(navArgument("clipId") { type = NavType.StringType }),
            ) { ConfirmScreen(onBack = { nav.navigateUp() }) }
        }
    }
}

/** launchSingleTop：快速連點不會推兩層確認頁。 */
private fun NavController.navigateToConfirm(clipId: String) {
    navigate("confirm/$clipId") { launchSingleTop = true }
}

/** 畫面回到前景（ON_RESUME）時呼叫 [action]；涵蓋首次進入、回前景、從其他畫面返回。 */
@Composable
private fun OnResume(action: () -> Unit) {
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
    throttle: ClickThrottle,
    onStartConfirm: (String) -> Unit,
    viewModel: ClipsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val quota by viewModel.quota.collectAsState()
    val unfinished by viewModel.unfinished.collectAsState()
    val hint by viewModel.blockedHint.collectAsState()
    OnResume(viewModel::onResume)
    when (val s = state) {
        Load.Loading -> LoadingBox()
        is Load.Error -> ErrorBox(s.message, viewModel::load, Modifier.fillMaxSize())
        is Load.Success -> LazyColumn(Modifier.fillMaxSize()) {
            item { QuotaHeader(quota, viewModel::refreshQuota) }
            if (unfinished.isNotEmpty()) {
                item {
                    Text(
                        "未完成的客串",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(unfinished, key = { it.pending.clientRequestId }) { row ->
                    UnfinishedRow(
                        row = row,
                        clips = s.value,
                        onContinue = { throttle.guarded { onStartConfirm(row.pending.clipId) } },
                        onRestart = {
                            throttle.guarded { if (viewModel.restart(row)) onStartConfirm(row.pending.clipId) }
                        },
                        onRemove = { viewModel.remove(row) },
                    )
                }
            }
            items(s.value, key = { it.id }) { clip ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            throttle.guarded { if (viewModel.tryStart()) onStartConfirm(clip.id) }
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
    hint?.let { q ->
        AlertDialog(
            onDismissRequest = viewModel::dismissHint,
            text = { Text("${q.display}，${formatTime(q.resetsAt)} 重置") },
            confirmButton = { TextButton(onClick = viewModel::dismissHint) { Text("知道了") } },
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
                Text(row.pending.lastError ?: "尚未確認是否建立成功")
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
    OnResume(viewModel::refreshPoints)
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
            LedgerPhase.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
            }
            LedgerPhase.Error -> item {
                ErrorBox(ledger.errorMessage, viewModel::loadFirstPage, Modifier.fillMaxWidth())
            }
            else -> {
                if (ledger.items.isEmpty()) {
                    item { Text("目前沒有點數明細", Modifier.padding(16.dp)) }
                }
                items(ledger.items, key = { it.id }) { LedgerRow(it) }
                when (ledger.phase) {
                    LedgerPhase.LoadingMore -> item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    LedgerPhase.LoadMoreError -> item {
                        ErrorBox(ledger.errorMessage, viewModel::loadMore, Modifier.fillMaxWidth())
                    }
                    else -> if (ledger.nextCursor != null) item {
                        // 捲到清單底部時自動載入下一頁
                        LaunchedEffect(ledger.nextCursor) { viewModel.loadMore() }
                    }
                }
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
