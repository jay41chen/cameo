package io.github.jay41chen.cameo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackSaveTest {
    @Test
    fun shouldPauseOnStop_followsConfigurationChange() {
        assertTrue(shouldPauseOnStop(false))
        assertFalse(shouldPauseOnStop(true))
    }

    @Test
    fun playbackToSave_playingAndConfigChange_resumes() {
        assertEquals(SavedPlayback(1234L, true), playbackToSave(1234L, true, true))
    }

    @Test
    fun playbackToSave_playingWithoutConfigChange_doesNotResume() {
        assertEquals(SavedPlayback(1234L, false), playbackToSave(1234L, true, false))
    }

    @Test
    fun playbackToSave_pausedWithConfigChange_doesNotResume() {
        assertEquals(SavedPlayback(1234L, false), playbackToSave(1234L, false, true))
    }

    @Test
    fun playbackToSave_keepsPositionAsIs() {
        assertEquals(0L, playbackToSave(0L, false, false).positionMs)
        assertEquals(98765L, playbackToSave(98765L, true, true).positionMs)
    }
}
