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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kimhit.cameo.domain.model.LedgerEntry
import com.kimhit.cameo.domain.model.LedgerType

private const val ROUTE_CLIPS = "clips"
private const val ROUTE_POINTS = "points"
private const val GENERIC_ERROR = "發生錯誤，請稍後再試"

@Composable
fun CameoRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    Scaffold(
        bottomBar = {
            NavigationBar {
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
            composable(ROUTE_CLIPS) { ClipsScreen() }
            composable(ROUTE_POINTS) { PointsScreen() }
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun ErrorBox(message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
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
fun ClipsScreen(viewModel: ClipsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        Load.Loading -> LoadingBox()
        is Load.Error -> ErrorBox(s.message, viewModel::load, Modifier.fillMaxSize())
        is Load.Success -> LazyColumn(Modifier.fillMaxSize()) {
            items(s.value, key = { it.id }) { clip ->
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(clip.title, style = MaterialTheme.typography.titleMedium)
                    Text("${clip.cost} 點")
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun PointsScreen(viewModel: PointsViewModel = hiltViewModel()) {
    val points by viewModel.points.collectAsState()
    val ledger by viewModel.ledger.collectAsState()
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
