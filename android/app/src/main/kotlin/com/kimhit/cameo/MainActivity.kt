package com.kimhit.cameo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.kimhit.cameo.domain.usecase.ResumeTrackingUseCase
import com.kimhit.cameo.tracking.CameoTrackService
import com.kimhit.cameo.tracking.CompletionNotifier
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { CameoRoot() }
            }
        }
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
