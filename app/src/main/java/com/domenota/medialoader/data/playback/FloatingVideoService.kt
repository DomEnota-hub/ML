package com.domenota.medialoader.data.playback

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.domenota.medialoader.MainActivity
import com.domenota.medialoader.R
import com.domenota.medialoader.core.logging.AppLog
import kotlin.math.roundToInt
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.privatevault.PrivateFileStore
import kotlinx.coroutines.*

/** App-owned floating window. System PiP reserves double taps and its controls for Android. */
@OptIn(UnstableApi::class)
class FloatingVideoService : Service() {
    companion object {
        private const val CHANNEL = "floating_video"
        private const val NOTIFICATION = 5042
        private const val EXTRA_URI = "uri"
        private const val EXTRA_ID = "id"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_PLAY = "play"

        fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun start(context: Context, id: String, uri: Uri, positionMs: Long, play: Boolean) {
            val intent = Intent(context, FloatingVideoService::class.java)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_POSITION, positionMs).putExtra(EXTRA_PLAY, play)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val privateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playbackJob: Job? = null
    private lateinit var manager: WindowManager
    private var window: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var player: ExoPlayer? = null
    private var itemId: String? = null
    private var controls: FrameLayout? = null
    private var playButton: ImageButton? = null
    private var expanded = false
    private val controlsHandler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { setControls(false) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            "Всплывающее видео", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(this, 5042, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Всплывающее видео")
            .setContentText("Нажмите, чтобы вернуться в приложение")
            .setContentIntent(launch).setOngoing(true).build()
        ServiceCompat.startForeground(this, NOTIFICATION, notice,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        val source = intent?.getStringExtra(EXTRA_URI)
        if (id.isNullOrBlank() || source.isNullOrBlank() || !canShow(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        playbackJob?.cancel()
        closeWindow()
        itemId = id
        playbackJob = privateScope.launch {
            try {
                val uri = Uri.parse(source)
                guardFloatingPlayback(id,
                    PrivateFileStore(this@FloatingVideoService).owns(source) || uri.path?.contains("/private-media/") == true,
                    MediaRepository.get(this@FloatingVideoService).privateMediaIds,
                    open = { openVideo(intent, uri) },
                    close = { closeWindow(); stopSelf() })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AppLog.e("FloatingVideo", "Could not validate floating playback", error)
                closeWindow()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun openVideo(intent: Intent, source: Uri) {
        val video = ExoPlayer.Builder(this).build().also { active ->
            active.setMediaItem(MediaItem.fromUri(source))
            active.seekTo(intent.getLongExtra(EXTRA_POSITION, 0L).coerceAtLeast(0L))
            active.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    BackgroundMediaWork.setBusy("floating-playback", isPlaying)
                    updatePlayButton(isPlaying)
                }
            })
            active.prepare()
            active.playWhenReady = intent.getBooleanExtra(EXTRA_PLAY, true)
        }
        player = video
        try {
            showWindow(video)
        } catch (error: Exception) {
            AppLog.e("FloatingVideo", "Could not attach floating window", error)
            stopSelf()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showWindow(video: ExoPlayer) {
        expanded = false
        val width = dp(260)
        val layout = WindowManager.LayoutParams(width, width * 9 / 16,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - width - dp(16)).coerceAtLeast(0)
            y = dp(72)
        }
        params = layout
        val root = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(18).toFloat()
            }
            clipToOutline = true
            elevation = dp(8).toFloat()
        }
        val videoView = PlayerView(this).apply {
            player = video
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
        }
        root.addView(videoView, FrameLayout.LayoutParams(-1, -1))

        val taps = View(this)
        root.addView(taps, FrameLayout.LayoutParams(-1, -1))
        val layer = FrameLayout(this).apply { setBackgroundColor(0x66000000) }
        controls = layer
        val center = imageButton(R.drawable.ic_floating_pause, "Пауза или воспроизведение") {
            if (video.isPlaying) video.pause() else video.play()
            setControls(true)
        }
        playButton = center
        layer.addView(center, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))
        val expand = imageButton(R.drawable.ic_floating_expand, "Развернуть плеер") {
            expandToApp()
        }
        layer.addView(expand, FrameLayout.LayoutParams(dp(44), dp(44),
            Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(32)) })
        val close = imageButton(R.drawable.ic_floating_close, "Закрыть плеер") {
            video.pause()
            stopSelf()
        }
        layer.addView(close, FrameLayout.LayoutParams(dp(40), dp(40),
            Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        layer.visibility = View.GONE
        root.addView(FloatingVideoSeekBar(this, video) { scrubbing ->
            controlsHandler.removeCallbacks(hideControls)
            if (!scrubbing && expanded) controlsHandler.postDelayed(hideControls, 3_000L)
        }, FrameLayout.LayoutParams(-1, dp(32), Gravity.BOTTOM))

        val detector = object : GestureDetector.SimpleOnGestureListener() {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0

            override fun onDown(e: MotionEvent): Boolean {
                controlsHandler.removeCallbacks(hideControls)
                downRawX = e.rawX
                downRawY = e.rawY
                startX = layout.x
                startY = layout.y
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setControls(!expanded)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val delta = FloatingVideoGesturePolicy.seekDelta(layout.width,
                    resources.displayMetrics.density, e.x)
                if (delta != null) {
                    val duration = video.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                    video.seekTo((video.currentPosition + delta).coerceIn(0L, duration))
                } else if (layout.width >= dp(FloatingVideoGesturePolicy.MIN_SEEK_WIDTH_DP)) {
                    if (video.isPlaying) video.pause() else video.play()
                }
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float,
                distanceY: Float): Boolean {
                layout.x = FloatingVideoGesturePolicy.clampX(
                    startX + (e2.rawX - downRawX).roundToInt(), layout.width,
                    resources.displayMetrics.widthPixels)
                layout.y = (startY + (e2.rawY - downRawY).roundToInt()).coerceIn(0,
                    (resources.displayMetrics.heightPixels - layout.height).coerceAtLeast(0))
                manager.updateViewLayout(root, layout)
                return true
            }
        }
        val gestures = GestureDetector(this, detector)
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val max = FloatingVideoGesturePolicy.maxWidth(resources.displayMetrics.widthPixels)
                layout.width = (layout.width * detector.scaleFactor).roundToInt()
                    .coerceIn(dp(220).coerceAtMost(max), max)
                layout.height = layout.width * 9 / 16
                layout.x = FloatingVideoGesturePolicy.clampX(layout.x, layout.width, max)
                manager.updateViewLayout(root, layout)
                return true
            }
        })
        var suppressDragUntilNextTouch = false
        val touch = View.OnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) suppressDragUntilNextTouch = false
            scale.onTouchEvent(event)
            if (scale.isInProgress || event.pointerCount > 1) suppressDragUntilNextTouch = true
            if (!suppressDragUntilNextTouch) gestures.onTouchEvent(event)
            if ((event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) && expanded) {
                controlsHandler.removeCallbacks(hideControls)
                controlsHandler.postDelayed(hideControls, 3_000L)
            }
            true
        }
        taps.setOnTouchListener(touch)
        layer.setOnTouchListener(touch)
        manager.addView(root, layout)
        window = root
        updatePlayButton(video.isPlaying)
    }

    private fun imageButton(icon: Int, description: String, click: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = description
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = null
            setOnClickListener { click() }
        }

    private fun updatePlayButton(playing: Boolean) {
        playButton?.setImageResource(if (playing) R.drawable.ic_floating_pause
            else R.drawable.ic_floating_play)
    }

    private fun setControls(show: Boolean) {
        controlsHandler.removeCallbacks(hideControls)
        expanded = show
        controls?.visibility = if (show) View.VISIBLE else View.GONE
        if (show) controlsHandler.postDelayed(hideControls, 3_000L)
    }

    private fun savePosition() {
        val id = itemId ?: return
        val active = player ?: return
        val prefs = getSharedPreferences("video_playback_positions", Context.MODE_PRIVATE)
        VideoWatchProgress.record(prefs, id, active.currentPosition, active.duration)
        VideoWatchProgress.saveResume(prefs, id, active.currentPosition)
        prefs.edit().putBoolean("floating_resume_play_$id", active.isPlaying).apply()
    }

    private fun expandToApp() {
        savePosition()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
        stopSelf()
    }

    private fun closeWindow() {
        controlsHandler.removeCallbacks(hideControls)
        savePosition()
        window?.let { runCatching { manager.removeView(it) } }
        window = null
        controls = null
        playButton = null
        player?.release()
        player = null
        BackgroundMediaWork.setBusy("floating-playback", false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val current = params ?: return
        val root = window ?: return
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        current.width = current.width.coerceAtMost(FloatingVideoGesturePolicy.maxWidth(width))
        current.height = current.width * 9 / 16
        current.x = FloatingVideoGesturePolicy.clampX(current.x, current.width, width)
        current.y = current.y.coerceIn(0, (height - current.height).coerceAtLeast(0))
        manager.updateViewLayout(root, current)
    }

    override fun onDestroy() {
        privateScope.cancel()
        BackgroundMediaWork.setBusy("floating-playback", false)
        closeWindow()
        AppLog.i("FloatingVideo", "Floating window closed")
        super.onDestroy()
    }
}
