package com.baba.edgedock.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.baba.edgedock.EdgeDockApp
import com.baba.edgedock.MainActivity
import com.baba.edgedock.R
import com.baba.edgedock.data.UserPreferences
import com.baba.edgedock.ui.theme.EdgeDockTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var preferences: UserPreferences
    private var overlayView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val lifecycleOwner = OverlayLifecycleOwner()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val expandedState = mutableStateOf(false)

    private var bubbleX = 0
    private var bubbleY = 600
    private var edgeSnapAnimator: ValueAnimator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        preferences = UserPreferences(applicationContext)

        // Must call startForeground promptly after startForegroundService — even when
        // we immediately stop because overlay permission is missing.
        promoteToForeground()

        if (!Settings.canDrawOverlays(this)) {
            serviceScope.launch { preferences.setDockEnabled(false) }
            stopSelfSafely()
            return
        }

        val saved = preferences.bubblePositionBlocking()
        bubbleX = saved.x
        bubbleY = saved.y

        running.set(true)
        addOverlayView()
        serviceScope.launch {
            preferences.setDockEnabled(true)
            (application as EdgeDockApp).appRepository.warm()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch { preferences.setDockEnabled(false) }
                stopSelfSafely()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        edgeSnapAnimator?.cancel()
        serviceScope.cancel()
        removeOverlayView()
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopSelfSafely() {
        running.set(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun addOverlayView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        lifecycleOwner.performRestore()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                EdgeDockTheme {
                    DockOverlayContent(
                        expanded = expandedState.value,
                        onExpandedChange = { setExpanded(it) },
                        onBubbleDrag = { dx, dy -> onBubbleDrag(dx, dy) },
                        onBubbleDragEnd = { onBubbleDragEnd() },
                    )
                }
            }
        }
        overlayView = composeView

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX
            y = bubbleY
        }
        layoutParams = params

        windowManager.addView(composeView, params)
    }

    private fun removeOverlayView() {
        overlayView?.let { view ->
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            lifecycleOwner.clear()
            runCatching { windowManager.removeViewImmediate(view) }
        }
        overlayView = null
        layoutParams = null
    }

    /** Exactly one window resize per open/close transition - see plan doc for why. */
    private fun setExpanded(expanded: Boolean) {
        val params = layoutParams ?: return
        val view = overlayView ?: return

        if (expanded) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.x = 0
            params.y = 0
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            // Keep IME hidden until the user taps the search field.
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.setBlurBehindRadius(56)
            }
            // Overlay windows are not Activities — handle system back here.
            view.isFocusableInTouchMode = true
            view.requestFocus()
            view.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    setExpanded(false)
                    true
                } else {
                    false
                }
            }
        } else {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.x = bubbleX
            params.y = bubbleY
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                params.setBlurBehindRadius(0)
            }
            view.setOnKeyListener(null)
        }
        windowManager.updateViewLayout(view, params)
        expandedState.value = expanded
    }

    private fun onBubbleDrag(dxPx: Float, dyPx: Float) {
        val params = layoutParams ?: return
        val view = overlayView ?: return
        edgeSnapAnimator?.cancel()

        val bounds = windowManager.currentWindowMetrics.bounds
        bubbleX = (bubbleX + dxPx.toInt()).coerceIn(0, (bounds.width() - view.width).coerceAtLeast(0))
        bubbleY = (bubbleY + dyPx.toInt()).coerceIn(0, (bounds.height() - view.height).coerceAtLeast(0))
        params.x = bubbleX
        params.y = bubbleY
        windowManager.updateViewLayout(view, params)
    }

    private fun onBubbleDragEnd() {
        val view = overlayView ?: return
        val bounds = windowManager.currentWindowMetrics.bounds
        val bubbleWidthPx = view.width.takeIf { it > 0 } ?: dpToPx(56)
        val targetX = if (bubbleX + bubbleWidthPx / 2 < bounds.width() / 2) 0 else bounds.width() - bubbleWidthPx
        animateBubbleTo(targetX, bubbleY)
    }

    private fun animateBubbleTo(targetX: Int, targetY: Int) {
        val params = layoutParams ?: return
        val view = overlayView ?: return
        val startX = bubbleX

        edgeSnapAnimator?.cancel()
        edgeSnapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                bubbleX = (startX + (targetX - startX) * fraction).toInt()
                bubbleY = targetY
                params.x = bubbleX
                params.y = bubbleY
                windowManager.updateViewLayout(view, params)
            }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        serviceScope.launch {
                            preferences.setBubblePosition(bubbleX, bubbleY)
                        }
                    }
                },
            )
            start()
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun buildNotification(): Notification {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            notificationManager.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "edgedock_overlay"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.baba.edgedock.action.STOP"

        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            CoroutineScope(Dispatchers.Default).launch {
                UserPreferences(context.applicationContext).setDockEnabled(false)
            }
            running.set(false)
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
