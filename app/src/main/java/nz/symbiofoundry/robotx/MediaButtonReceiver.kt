package nz.symbiofoundry.robotx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent

/** Diagnostic/fallback receiver retained from accepted H1. */
class MediaButtonReceiver : BroadcastReceiver() {
    companion object { private const val TAG = "RobotXHeadset" }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return

        val event: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

        if (event == null) {
            Log.w(TAG, "RXH_PROD_MEDIA_RECEIVER_EVENT")
            return
        }

        val actionName = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            else -> event.action.toString()
        }

        Log.i(TAG, "RXH_PROD_MEDIA_RECEIVER_EVENT")
    }
}
