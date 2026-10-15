package com.driving.hud.usbcamera.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.driving.hud.usbcamera.util.HudLogger

class HudSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "HudSurfaceView"
    }

    /** Called with the Surface on create/change, and null when destroyed. */
    var surfaceListener: ((Surface?) -> Unit)? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        HudLogger.i(TAG, "Surface created")
        surfaceListener?.invoke(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        HudLogger.i(TAG, "Surface changed: format=$format, size=${width}x$height")
        surfaceListener?.invoke(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        HudLogger.i(TAG, "Surface destroyed")
        surfaceListener?.invoke(null)
    }
}
