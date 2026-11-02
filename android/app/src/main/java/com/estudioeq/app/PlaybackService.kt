package com.estudioeq.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle

class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "estudio_master_hires_playback"
        const val NOTIF_ID = 1002

        const val ACTION_UPDATE = "com.estudiomaster.hires.ACTION_UPDATE"
        const val ACTION_GLOBAL_SYNC = "com.estudiomaster.hires.ACTION_GLOBAL_SYNC"
        const val ACTION_TOGGLE = "com.estudiomaster.hires.ACTION_TOGGLE"
        const val ACTION_NEXT = "com.estudiomaster.hires.ACTION_NEXT"
        const val ACTION_PREV = "com.estudiomaster.hires.ACTION_PREV"
        const val ACTION_STOP = "com.estudiomaster.hires.ACTION_STOP"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_PLAYING = "extra_playing"
        const val EXTRA_POS = "extra_pos"
        const val EXTRA_DUR = "extra_dur"

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var instance: PlaybackService? = null

        @Volatile
        var commandHandler: ((String, Long) -> Unit)? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        fun updateStateFast(
            context: Context,
            title: String,
            artist: String,
            isPlaying: Boolean,
            posMs: Long,
            durMs: Long
        ) {
            val svc = instance
            if (svc != null && svc.isInForeground) {
                mainHandler.post {
                    svc.applyUpdateDirect(title, artist, isPlaying, posMs, durMs)
                }
                return
            }
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, PlaybackService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_PLAYING, isPlaying)
                putExtra(EXTRA_POS, posMs)
                putExtra(EXTRA_DUR, durMs)
            }
            try {
                if (isPlaying || GlobalAudioEngine.isGlobalActive()) {
                    ContextCompat.startForegroundService(appCtx, intent)
                } else if (isRunning) {
                    appCtx.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun notifyGlobalModeChanged(context: Context, enabled: Boolean) {
            val svc = instance
            if (svc != null && svc.isInForeground) {
                mainHandler.post {
                    svc.onGlobalModeChangedInternal()
                }
                return
            }
            if (enabled) {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, PlaybackService::class.java).apply {
                    action = ACTION_GLOBAL_SYNC
                }
                try {
                    ContextCompat.startForegroundService(appCtx, intent)
                } catch (_: Exception) {
                }
            }
        }
    }

    private lateinit var mediaSession: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var cachedArt: Bitmap? = null
    private var noisyRegistered = false
    private var fxSessionRegistered = false

    @Volatile
    private var isInForeground = false

    private var curTitle: String = "Estudio Master Hi-Res"
    private var curArtist: String = "Motor Directo 1:1"
    private var curPlaying: Boolean = false
    private var curPosMs: Long = 0L
    private var curDurMs: Long = 0L

    private var lastNotifSignature: String = ""
    private var lastPlaybackStatePushMs: Long = 0L
    private var lastPlaybackStatePlaying: Boolean? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && curPlaying) {
                commandHandler?.invoke("pause_user", 0L)
            }
        }
    }

    private val externalAudioSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ctx = context ?: return
            val act = intent?.action ?: return
            val sid = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
            if (sid <= 0) return
            when (act) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->
                    GlobalAudioEngine.onExternalAudioSessionOpened(ctx, sid, pkg)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION ->
                    GlobalAudioEngine.onExternalAudioSessionClosed(ctx, sid, pkg)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        GlobalAudioEngine.ensureInitialized(applicationContext)
        createChannel()

        try {
            cachedArt = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        } catch (_: Exception) {
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EstudioMasterHiRes::AudioWakeLock").apply {
            setReferenceCounted(false)
        }

        registerExternalSessionReceiver()

        // No pedimos AudioFocus aquí para no robarle el AudioFocus interno a AudioWebView.
        mediaSession = MediaSessionCompat(this, "EstudioMasterHiResSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    commandHandler?.invoke("play", 0L)
                }

                override fun onPause() {
                    commandHandler?.invoke("pause_user", 0L)
                }

                override fun onSkipToNext() {
                    commandHandler?.invoke("next", 0L)
                }

                override fun onSkipToPrevious() {
                    commandHandler?.invoke("prev", 0L)
                }

                override fun onSeekTo(pos: Long) {
                    commandHandler?.invoke("seek", pos)
                }

                override fun onStop() {
                    commandHandler?.invoke("pause_user", 0L)
                    if (!GlobalAudioEngine.isGlobalActive()) {
                        isInForeground = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> commandHandler?.invoke("toggle_user", 0L)
            ACTION_NEXT -> commandHandler?.invoke("next", 0L)
            ACTION_PREV -> commandHandler?.invoke("prev", 0L)
            ACTION_STOP -> {
                commandHandler?.invoke("pause_user", 0L)
                if (!GlobalAudioEngine.isGlobalActive()) {
                    releaseHolders()
                    isInForeground = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                curPlaying = false
                updateSessionAndNotification(forceState = true)
            }
            ACTION_GLOBAL_SYNC -> {
                onGlobalModeChangedInternal()
            }
            ACTION_UPDATE, null -> {
                if (intent != null) {
                    curTitle = intent.getStringExtra(EXTRA_TITLE) ?: curTitle
                    curArtist = intent.getStringExtra(EXTRA_ARTIST) ?: curArtist
                    curPlaying = intent.getBooleanExtra(EXTRA_PLAYING, curPlaying)
                    curPosMs = intent.getLongExtra(EXTRA_POS, curPosMs)
                    curDurMs = intent.getLongExtra(EXTRA_DUR, curDurMs)
                }
                syncHoldersState()
                updateSessionAndNotification(forceState = true)
            }
        }
        return START_STICKY
    }

    internal fun applyUpdateDirect(
        title: String,
        artist: String,
        isPlaying: Boolean,
        posMs: Long,
        durMs: Long
    ) {
        val stateChanged = (curPlaying != isPlaying) || (curTitle != title) || (curArtist != artist)
        curTitle = title
        curArtist = artist
        curPlaying = isPlaying
        curPosMs = posMs
        curDurMs = durMs
        syncHoldersState()
        updateSessionAndNotification(forceState = stateChanged)
    }

    internal fun onGlobalModeChangedInternal() {
        syncHoldersState()
        lastNotifSignature = ""
        updateSessionAndNotification(forceState = true)
        if (!curPlaying && !GlobalAudioEngine.isGlobalActive() && isInForeground) {
            isInForeground = false
            stopForeground(STOP_FOREGROUND_DETACH)
        }
    }

    private fun syncHoldersState() {
        if (curPlaying || GlobalAudioEngine.isGlobalActive()) {
            acquireHolders()
        } else {
            releaseHolders()
        }
    }

    private fun registerExternalSessionReceiver() {
        if (fxSessionRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            }
            ContextCompat.registerReceiver(
                this,
                externalAudioSessionReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            fxSessionRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun acquireHolders() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(12 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }

        if (!noisyRegistered) {
            try {
                ContextCompat.registerReceiver(
                    this,
                    noisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    ContextCompat.RECEIVER_EXPORTED
                )
                noisyRegistered = true
            } catch (_: Exception) {
            }
        }
    }

    private fun releaseHolders() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        if (noisyRegistered) {
            try {
                unregisterReceiver(noisyReceiver)
            } catch (_: Exception) {
            }
            noisyRegistered = false
        }
    }

    private fun updateSessionAndNotification(forceState: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (forceState || lastPlaybackStatePlaying != curPlaying || (now - lastPlaybackStatePushMs >= 5000L)) {
            lastPlaybackStatePushMs = now
            lastPlaybackStatePlaying = curPlaying
            val stateActions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP

            val playbackState = PlaybackStateCompat.Builder()
                .setActions(stateActions)
                .setState(
                    if (curPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    curPosMs,
                    if (curPlaying) 1.0f else 0.0f
                )
                .build()
            mediaSession.setPlaybackState(playbackState)
        }

        val globalOn = GlobalAudioEngine.isGlobalActive()
        val displayTitle = if (!curPlaying && globalOn && curTitle == "Estudio Master Hi-Res") {
            "Ecualización Global DVC Activa"
        } else {
            curTitle
        }
        val displayArtist = if (!curPlaying && globalOn) {
            "Procesando reproductores externos · Motor 64-bit"
        } else {
            curArtist
        }

        val sig = "$displayTitle|$displayArtist|$curPlaying|$globalOn|$curDurMs"
        if (sig == lastNotifSignature && isInForeground) {
            return
        }
        lastNotifSignature = sig

        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
        if (curDurMs > 0L) {
            metaBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, curDurMs)
        }
        cachedArt?.let {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
        mediaSession.setMetadata(metaBuilder.build())

        val notification = buildNotification(displayTitle, displayArtist, curPlaying || globalOn)
        if (curPlaying || globalOn || !isInForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            isInForeground = true
        } else {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(title: String, artist: String, ongoing: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevPending = serviceActionPending(ACTION_PREV, 1)
        val togglePending = serviceActionPending(ACTION_TOGGLE, 2)
        val nextPending = serviceActionPending(ACTION_NEXT, 3)

        val toggleIcon = if (curPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
        val toggleLabel = if (curPlaying) "Pausar" else "Reproducir"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_eq)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentPending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(ongoing)
            .addAction(R.drawable.ic_notif_prev, "Anterior", prevPending)
            .addAction(toggleIcon, toggleLabel, togglePending)
            .addAction(R.drawable.ic_notif_next, "Siguiente", nextPending)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun serviceActionPending(action: String, reqCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reproducción de Estudio Master Hi-Res",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Controles en pantalla de bloqueo y reproducción continua"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        isRunning = false
        isInForeground = false
        releaseHolders()
        if (fxSessionRegistered) {
            try {
                unregisterReceiver(externalAudioSessionReceiver)
            } catch (_: Exception) {
            }
            fxSessionRegistered = false
        }
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
