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
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
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
    private val morphProgress = mutableStateOf(0f)
    /** True only after the open morph has finished, so the list stays out of the animation. */
    private val panelSettled = mutableStateOf(false)
    private val bubbleXState = mutableStateOf(0)
    private val bubbleYState = mutableStateOf(600)

    private var edgeSnapAnimator: ValueAnimator? = null
    private var morphAnimator: ValueAnimator? = null
    private var morphEdge = 0f
    private var touchView: View? = null
    private var touchParams: WindowManager.LayoutParams? = null

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
        bubbleXState.value = saved.x
        bubbleYState.value = saved.y

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
        morphAnimator?.apply {
            removeAllListeners()
            cancel()
        }
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
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setContent {
                EdgeDockTheme {
                    DockOverlayContent(
                        expanded = expandedState.value,
                        // Read inside draw/graphicsLayer only, so frames do not recompose the list.
                        morphProgress = { morphProgress.value },
                        panelSettled = panelSettled.value,
                        bubbleX = bubbleXState.value,
                        bubbleY = bubbleYState.value,
                        onExpandedChange = { expanded ->
                            if (expanded) expand() else collapse()
                        },
                        onBubbleDrag = { dx, dy -> onBubbleDrag(dx, dy) },
                        onBubbleDragEnd = { onBubbleDragEnd() },
                    )
                }
            }
        }
        overlayView = composeView

        // Fixed fullscreen window. The morph is drawn inside it, so the window bounds
        // never change mid-animation (that resize is what jumps toward the screen corner).
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags(touchable = false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            coverSystemBars()
        }
        layoutParams = params
        windowManager.addView(composeView, params)
        addTouchWindow()
    }

    /**
     * Overlay windows are inset below the status bar unless told otherwise, which leaves
     * an undimmed strip at the top and shifts the bubble's touch target off the icon.
     */
    private fun WindowManager.LayoutParams.coverSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
            setFitInsetsIgnoringVisibility(true)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        alpha = 1f
    }

    private fun overlayFlags(touchable: Boolean): Int {
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return if (touchable) base else base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }

    /** Invisible hit target for the bubble. The fullscreen window only draws. */
    private fun addTouchWindow() {
        val bubblePx = dpToPx(BUBBLE_DP)
        val touch = View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { _, event -> onTouchBubble(event) }
        }
        val params = WindowManager.LayoutParams(
            bubblePx,
            bubblePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = bubbleXState.value
            y = bubbleYState.value
            coverSystemBars()
        }
        touchView = touch
        touchParams = params
        windowManager.addView(touch, params)
    }

    private fun removeOverlayView() {
        touchView?.let { view -> runCatching { windowManager.removeViewImmediate(view) } }
        touchView = null
        touchParams = null
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

    private fun expand() {
        val view = overlayView ?: return
        if (expandedState.value && morphEdge > 0.99f) return
        edgeSnapAnimator?.cancel()
        setTouchInteractive(false)
        setOverlayTouchable(true)
        panelSettled.value = false
        expandedState.value = true
        animateMorph(to = 1f)
    }

    private fun collapse() {
        val view = overlayView ?: return
        if (!expandedState.value && morphEdge < 0.01f) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        panelSettled.value = false
        clearPanelFocus()
        animateMorph(to = 0f)
    }

    private fun animateMorph(to: Float) {
        val start = morphEdge
        morphAnimator?.apply {
            removeAllListeners()
            removeAllUpdateListeners()
            cancel()
        }
        morphAnimator = null
        if (abs(start - to) < 0.001f) {
            morphEdge = to
            morphProgress.value = to
            if (to == 0f) {
                finishCollapse()
            } else {
                panelSettled.value = true
                enablePanelFocus()
            }
            return
        }
        val opening = to > start
        val fullMs = if (opening) OPEN_MS else MORPH_MS
        val animator = ValueAnimator.ofFloat(start, to).apply {
            duration = (fullMs * abs(to - start)).toLong().coerceIn(90L, fullMs)
            interpolator = if (opening) {
                PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
            } else {
                PathInterpolator(0.4f, 0f, 0.2f, 1f)
            }
            addUpdateListener {
                val t = it.animatedValue as Float
                morphEdge = t
                morphProgress.value = t
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (morphAnimator != animation) return
                        morphEdge = to
                        morphProgress.value = to
                        if (to == 0f) {
                            finishCollapse()
                        } else {
                            panelSettled.value = true
                            enablePanelFocus()
                        }
                    }
                },
            )
        }
        morphAnimator = animator
        animator.start()
    }

    private fun finishCollapse() {
        val view = overlayView ?: return
        expandedState.value = false
        panelSettled.value = false
        morphProgress.value = 0f
        morphEdge = 0f
        clearPanelFocus()
        setOverlayTouchable(false)
        placeTouchWindow(bubbleXState.value, bubbleYState.value)
        setTouchInteractive(true)
        view.setOnKeyListener(null)
    }

    private fun enablePanelFocus() {
        val params = layoutParams ?: return
        val view = overlayView ?: return
        if (!expandedState.value) return
        params.flags = overlayFlags(touchable = true) and
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                collapse()
                true
            } else {
                false
            }
        }
        params.coverSystemBars()
        runCatching { windowManager.updateViewLayout(view, params) }
        view.requestFocus()
    }

    private fun clearPanelFocus() {
        val params = layoutParams ?: return
        val view = overlayView ?: return
        params.flags = overlayFlags(touchable = expandedState.value || morphEdge > 0.01f)
        view.setOnKeyListener(null)
        view.clearFocus()
        params.coverSystemBars()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun setOverlayTouchable(touchable: Boolean) {
        val params = layoutParams ?: return
        val view = overlayView ?: return
        val keepFocus = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
        params.flags = overlayFlags(touchable)
        if (keepFocus && touchable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        }
        params.coverSystemBars()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun setTouchInteractive(interactive: Boolean) {
        val params = touchParams ?: return
        val view = touchView ?: return
        params.flags = if (interactive) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        params.coverSystemBars()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private var touchDownRawX = 0f
    private var touchDownRawY = 0f
    private var touchLastRawX = 0f
    private var touchLastRawY = 0f
    private var touchDragging = false

    private fun onTouchBubble(event: MotionEvent): Boolean {
        if (expandedState.value) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeSnapAnimator?.cancel()
                touchDownRawX = event.rawX
                touchDownRawY = event.rawY
                touchLastRawX = event.rawX
                touchLastRawY = event.rawY
                touchDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchLastRawX
                val dy = event.rawY - touchLastRawY
                touchLastRawX = event.rawX
                touchLastRawY = event.rawY
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                val travelledX = event.rawX - touchDownRawX
                val travelledY = event.rawY - touchDownRawY
                if (!touchDragging && travelledX * travelledX + travelledY * travelledY > slop * slop) {
                    touchDragging = true
                }
                if (touchDragging) onBubbleDrag(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touchDragging) onBubbleDragEnd() else expand()
            }
        }
        return true
    }

    private fun onBubbleDrag(dxPx: Float, dyPx: Float) {
        val bounds = windowManager.currentWindowMetrics.bounds
        val bubblePx = dpToPx(BUBBLE_DP)
        val x = (bubbleXState.value + dxPx.toInt()).coerceIn(0, (bounds.width() - bubblePx).coerceAtLeast(0))
        val y = (bubbleYState.value + dyPx.toInt()).coerceIn(0, (bounds.height() - bubblePx).coerceAtLeast(0))
        placeTouchWindow(x, y)
    }

    private fun onBubbleDragEnd() {
        val bounds = windowManager.currentWindowMetrics.bounds
        val bubblePx = dpToPx(BUBBLE_DP)
        val x = bubbleXState.value
        val targetX = if (x + bubblePx / 2 < bounds.width() / 2) 0 else bounds.width() - bubblePx
        animateBubbleTo(targetX, bubbleYState.value)
    }

    private fun placeTouchWindow(x: Int, y: Int) {
        val params = touchParams ?: return
        val view = touchView ?: return
        bubbleXState.value = x
        bubbleYState.value = y
        params.x = x
        params.y = y
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun animateBubbleTo(targetX: Int, targetY: Int) {
        val startX = bubbleXState.value

        edgeSnapAnimator?.cancel()
        edgeSnapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val x = (startX + (targetX - startX) * fraction).toInt()
                placeTouchWindow(x, targetY)
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        serviceScope.launch {
                            preferences.setBubblePosition(bubbleXState.value, bubbleYState.value)
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
        private const val BUBBLE_DP = 56
        private const val OPEN_MS = 200L
        private const val MORPH_MS = 280L
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
