package io.github.jay41chen.cameo.ui

import android.app.NotificationManager
import io.github.jay41chen.cameo.tracking.isChannelBlocked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationGateDecisionTest {
    @Test
    fun enabled_proceeds_onAnyApi() {
        listOf(26, 32, 33, 36).forEach {
            assertEquals(NotificationGate.PROCEED, decideNotificationGate(true, it, afterDenial = true, shouldShowRationale = false))
        }
    }

    @Test
    fun disabled_api33_onPress_requestsPermission() {
        assertEquals(
            NotificationGate.REQUEST_PERMISSION,
            decideNotificationGate(false, 33, afterDenial = false, shouldShowRationale = false),
        )
    }

    @Test
    fun disabled_api33_afterDenialWithRationale_requestsPermissionAgain() {
        assertEquals(
            NotificationGate.REQUEST_PERMISSION,
            decideNotificationGate(false, 36, afterDenial = true, shouldShowRationale = true),
        )
    }

    @Test
    fun disabled_api33_afterDenialAndNoRationale_needsSettings() {
        assertEquals(
            NotificationGate.NEED_SETTINGS,
            decideNotificationGate(false, 33, afterDenial = true, shouldShowRationale = false),
        )
    }

    @Test
    fun disabled_belowApi33_needsSettings_evenOnPress() {
        assertEquals(
            NotificationGate.NEED_SETTINGS,
            decideNotificationGate(false, 32, afterDenial = false, shouldShowRationale = false),
        )
        assertEquals(
            NotificationGate.NEED_SETTINGS,
            decideNotificationGate(false, 26, afterDenial = false, shouldShowRationale = true),
        )
    }

    @Test
    fun appEnabledButChannelBlocked_alwaysNeedsSettings() {
        listOf(26, 32, 33, 36).forEach {
            assertEquals(
                NotificationGate.NEED_SETTINGS,
                decideNotificationGate(true, it, afterDenial = false, shouldShowRationale = true, channelBlocked = true),
            )
        }
    }

    @Test
    fun appDisabled_channelBlockedFlagDoesNotChangeNormalPath() {
        assertEquals(
            NotificationGate.REQUEST_PERMISSION,
            decideNotificationGate(false, 33, afterDenial = false, shouldShowRationale = false, channelBlocked = true),
        )
    }

    @Test
    fun channelBlocked_onlyForImportanceNone_nullMeansNotCreatedYet() {
        assertTrue(isChannelBlocked(NotificationManager.IMPORTANCE_NONE))
        assertFalse(isChannelBlocked(null))
        assertFalse(isChannelBlocked(NotificationManager.IMPORTANCE_LOW))
        assertFalse(isChannelBlocked(NotificationManager.IMPORTANCE_HIGH))
    }
}
