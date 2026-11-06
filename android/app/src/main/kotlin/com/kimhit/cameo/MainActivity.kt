package com.kimhit.cameo

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.kimhit.cameo.domain.usecase.ResumeTrackingUseCase
import com.kimhit.cameo.tracking.CameoTrackService
import com.kimhit.cameo.tracking.CompletionNotifier
import com.kimhit.cameo.tracking.EXTRA_OPEN_MY_CAMEOS
import com.kimhit.cameo.tracking.shouldOpenMyCameos
import com.kimhit.cameo.tracking.TRACK_TAG
import com.kimhit.cameo.ui.CameoRoot
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var resumeTracking: ResumeTrackingUseCase

    private val notifier by lazy { CompletionNotifier(this) }
    private var resumeJob: Job? = null

    /** 點完成通知時 +1；只增不減、不存 saved state，Activity 重建後歸零（旋轉不會再跳頁）。 */
    private var openMyCameosRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleOpenIntent(intent, restored = savedInstanceState != null)
        setContent {
            MaterialTheme {
                Surface { CameoRoot(openMyCameosRequest) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent, restored = false)
    }

    private fun handleOpenIntent(intent: Intent, restored: Boolean) {
        val open = shouldOpenMyCameos(
            hasExtra = intent.hasExtra(EXTRA_OPEN_MY_CAMEOS),
            launchedFromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0,
            restored = restored,
        )
        if (open) openMyCameosRequest++
        // 不論結果都清掉，同一個 intent 不會被重複處理
        intent.removeExtra(EXTRA_OPEN_MY_CAMEOS)
    }

    /** 回到前景：對所有未完成草稿查一次訂單，補發完成通知、重新啟動追蹤服務。 */
    override fun onStart() {
        super.onStart()
        if (resumeJob?.isActive == true) return
        resumeJob = lifecycleScope.launch { resumeTrackingOnce() }
    }

    private suspend fun resumeTrackingOnce() {
        // 每筆認領成功就立刻通知（認領＋通知在 usecase 內不可取消）
        val track = resumeTracking(notifier.isEnabled()) { notifier.notifyDone(it) }
        // 查詢期間若已離開前景，不啟動前景服務（會被系統拒絕）；下次回前景再接手
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        track.forEach { id ->
            runCatching { CameoTrackService.start(this, id) }
                .onFailure { Log.w(TRACK_TAG, "resume start service failed id=$id", it) }
        }
    }
}
