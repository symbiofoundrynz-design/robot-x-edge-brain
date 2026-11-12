package nz.symbiofoundry.robotx

internal class HeadsetTriggerPolicy {
    private var lastKeyCode: Int? = null
    private var lastDownTime: Long? = null

    fun shouldTrigger(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        downTime: Long,
    ): Boolean {
        if (action != ACTION_DOWN) return false
        if (repeatCount != 0) return false
        if (keyCode !in ACCEPTED_MEDIA_KEYS) return false

        val duplicate = lastKeyCode == keyCode && lastDownTime == downTime
        if (duplicate) return false

        lastKeyCode = keyCode
        lastDownTime = downTime
        return true
    }

    companion object {
        const val ACTION_DOWN = 0
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_MEDIA_PLAY = 126
        const val KEYCODE_MEDIA_PAUSE = 127

        private val ACCEPTED_MEDIA_KEYS = setOf(
            KEYCODE_MEDIA_PLAY_PAUSE,
            KEYCODE_MEDIA_PLAY,
            KEYCODE_MEDIA_PAUSE,
        )
    }
}
