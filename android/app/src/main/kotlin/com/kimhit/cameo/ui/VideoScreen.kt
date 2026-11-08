package com.kimhit.cameo.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient

internal const val UNSHARE_CONFIRM_TEXT = "取消後這個連結會永久失效，再分享會產生新連結。"

@Composable
fun VideoScreen(onBackHome: () -> Unit, viewModel: VideoViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        VideoUiState.Loading -> LoadingBox()
        is VideoUiState.LoadError -> ErrorBox(s.message, viewModel::retry, Modifier.fillMaxSize())
        VideoUiState.NotFound -> MessageWithHome("找不到這支影片", null, onBackHome)
        is VideoUiState.NotReady -> MessageWithHome(s.title, s.message, onBackHome)
        is VideoUiState.Ready -> ReadyContent(s, viewModel)
    }
}

@Composable
private fun MessageWithHome(title: String?, message: String?, onBackHome: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(title ?: GENERIC_ERROR)
        message?.let { Text(it) }
        Button(onClick = onBackHome) { Text("回首頁") }
    }
}

@Composable
private fun ReadyContent(state: VideoUiState.Ready, viewModel: VideoViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmUnshare by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.shareUrlToSend) {
        val url = state.shareUrlToSend ?: return@LaunchedEffect
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        context.startActivity(Intent.createChooser(send, null))
        viewModel.onShareSent()
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.cameo.clip.title, style = MaterialTheme.typography.titleMedium)
            VideoPlayer(viewModel.videoUrl, viewModel.okHttpClient)
            Button(onClick = viewModel::share, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("分享")
            }
            if (state.cameo.share?.active == true) {
                OutlinedButton(
                    onClick = { confirmUnshare = true },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("取消分享") }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (confirmUnshare) {
        AlertDialog(
            onDismissRequest = { confirmUnshare = false },
            text = { Text(UNSHARE_CONFIRM_TEXT) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnshare = false
                    viewModel.unshare()
                }) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { confirmUnshare = false }) { Text("取消") } },
        )
    }
}

internal data class SavedPlayback(val positionMs: Long, val resume: Boolean)

/** 設定變更（旋轉）時不暫停，其餘進背景都暫停。 */
internal fun shouldPauseOnStop(changingConfigurations: Boolean): Boolean = !changingConfigurations

/** 位置原樣保存；只有「播放中且設定變更」才在還原後續播。 */
internal fun playbackToSave(
    positionMs: Long,
    playWhenReady: Boolean,
    changingConfigurations: Boolean,
): SavedPlayback = SavedPlayback(positionMs, playWhenReady && changingConfigurations)

private const val PLAYBACK_KEY = "video_playback"

private fun restoredPlayback(raw: Any?): SavedPlayback {
    val list = raw as? List<*> ?: return SavedPlayback(0L, false)
    val position = list.getOrNull(0) as? Long ?: 0L
    val resume = list.getOrNull(1) as? Boolean ?: false
    return SavedPlayback(position, resume)
}

/**
 * 不自動播放。旋轉：播放中則續播、暫停則停在原位；進背景（非設定變更的 ON_STOP）暫停；
 * 程序被殺後還原：停在原位不播放。保存在 onSaveInstanceState 當下才讀播放器狀態。
 */
@Composable
private fun VideoPlayer(videoUrl: String, client: OkHttpClient) {
    val context = LocalContext.current
    val registry = LocalSaveableStateRegistry.current
    var failed by remember { mutableStateOf(false) }
    val player = remember(videoUrl) {
        val restored = restoredPlayback(registry?.consumeRestored(PLAYBACK_KEY))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client)))
            .build()
            .apply {
                playWhenReady = restored.resume
                setMediaItem(MediaItem.fromUri(videoUrl))
                prepare()
                seekTo(restored.positionMs)
            }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(player, owner, registry) {
        val activity = context.findActivity()
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP &&
                shouldPauseOnStop(activity?.isChangingConfigurations ?: false)
            ) {
                player.pause()
            }
        }
        owner.lifecycle.addObserver(observer)
        val entry = registry?.registerProvider(PLAYBACK_KEY) {
            val saved = playbackToSave(
                player.currentPosition,
                player.playWhenReady,
                activity?.isChangingConfigurations ?: false,
            )
            listOf(saved.positionMs, saved.resume)
        }
        onDispose {
            entry?.unregister()
            owner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    if (failed) {
        ErrorBox(
            "影片播放失敗",
            {
                failed = false
                player.prepare()
            },
            Modifier.fillMaxWidth(),
        )
    } else {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
    }
}
