package nz.symbiofoundry.robotx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadsetTriggerPolicyTest {
    @Test
    fun acceptedPauseDownTriggersOnce() {
        val policy = HeadsetTriggerPolicy()
        assertTrue(policy.shouldTrigger(HeadsetTriggerPolicy.KEYCODE_MEDIA_PAUSE, HeadsetTriggerPolicy.ACTION_DOWN, 0, 100L))
        assertFalse(policy.shouldTrigger(HeadsetTriggerPolicy.KEYCODE_MEDIA_PAUSE, HeadsetTriggerPolicy.ACTION_DOWN, 0, 100L))
    }

    @Test
    fun playAndPlayPauseAreAccepted() {
        val policy = HeadsetTriggerPolicy()
        assertTrue(policy.shouldTrigger(HeadsetTriggerPolicy.KEYCODE_MEDIA_PLAY, HeadsetTriggerPolicy.ACTION_DOWN, 0, 200L))
        assertTrue(policy.shouldTrigger(HeadsetTriggerPolicy.KEYCODE_MEDIA_PLAY_PAUSE, HeadsetTriggerPolicy.ACTION_DOWN, 0, 300L))
    }

    @Test
    fun actionUpRepeatAndUnprovenHeadsetHookAreRejected() {
        val policy = HeadsetTriggerPolicy()
        assertFalse(policy.shouldTrigger(HeadsetTriggerPolicy.KEYCODE_MEDIA_PLAY, 1, 0, 400L))
        assertFalse(policy.shouldTrigger(HeadsetTriggerPolicy.KEYCODE_MEDIA_PLAY, HeadsetTriggerPolicy.ACTION_DOWN, 1, 500L))
        assertFalse(policy.shouldTrigger(79, HeadsetTriggerPolicy.ACTION_DOWN, 0, 600L))
    }
}
