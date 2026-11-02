package com.estudioeq.app

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object GlobalAudioEngine {

    private const val PREFS_NAME = "estudio_master_global_dsp"
    private const val KEY_CONFIG = "global_dsp_config"

    private val dspExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val CONSOLE_FREQS = floatArrayOf(
        31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
    )

    data class DspSnapshot(
        val globalEnabled: Boolean = false,
        val dvcEnabled: Boolean = true,
        val advancedTracking: Boolean = true,
        val eqEnabled: Boolean = true,
        val limiterEnabled: Boolean = true,
        val compEnabled: Boolean = false,
        val preampDb: Float = 0f,
        val bands10: FloatArray = FloatArray(10),
        val bassPct: Float = 0f,
        val treblePct: Float = 0f,
        val compAttackMs: Float = 2.5f,
        val compReleaseMs: Float = 17.0f,
        val compRatio: Float = 8.7f,
        val compFreqHz: Float = 20000f,
        val compThresholdDb: Float = -50.0f,
        val compGainDb: Float = 0.0f,
        val masterCeilDb: Float = -0.1f
    )

    private class SessionAttachment(
        val sessionId: Int,
        var packageName: String,
        var dp: DynamicsProcessing? = null,
        var eq: Equalizer? = null,
        var le: LoudnessEnhancer? = null
    ) {
        fun releaseAll() {
            try {
                dp?.enabled = false
                dp?.release()
            } catch (_: Exception) {
            }
            try {
                eq?.enabled = false
                eq?.release()
            } catch (_: Exception) {
            }
            try {
                le?.enabled = false
                le?.release()
            } catch (_: Exception) {
            }
            dp = null
            eq = null
            le = null
        }
    }

    private val sessions = ConcurrentHashMap<Int, SessionAttachment>()
    private val knownSessionPackages = ConcurrentHashMap<Int, String>()

    @Volatile
    private var currentConfig = DspSnapshot()

    @Volatile
    private var initialized = false

    @Volatile
    private var playbackCallbackRegistered = false

    private var appContext: Context? = null

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            super.onPlaybackConfigChanged(configs)
            if (!currentConfig.globalEnabled || !currentConfig.advancedTracking) return
            // Refresca las sesiones conocidas cuando un reproductor externo cambia de pista
            dspExecutor.execute {
                reapplyAllSessionsInternal()
            }
        }
    }

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            appContext = appCtx
            try {
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val saved = prefs.getString(KEY_CONFIG, null)
                if (!saved.isNullOrBlank()) {
                    currentConfig = parseConfig(saved)
                }
            } catch (_: Exception) {
            }
            updatePlaybackCallbackRegistration(appCtx)
            initialized = true
        }
    }

    private fun updatePlaybackCallbackRegistration(context: Context) {
        mainHandler.post {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@post
                if (currentConfig.globalEnabled && currentConfig.advancedTracking && !playbackCallbackRegistered) {
                    am.registerAudioPlaybackCallback(playbackCallback, mainHandler)
                    playbackCallbackRegistered = true
                } else if ((!currentConfig.globalEnabled || !currentConfig.advancedTracking) && playbackCallbackRegistered) {
                    am.unregisterAudioPlaybackCallback(playbackCallback)
                    playbackCallbackRegistered = false
                }
            } catch (_: Exception) {
            }
        }
    }

    fun onExternalAudioSessionOpened(context: Context, sessionId: Int, pkg: String?) {
        if (sessionId <= 0) return
        val appCtx = context.applicationContext
        ensureInitialized(appCtx)
        val cleanPkg = pkg?.trim().orEmpty()
        if (cleanPkg == appCtx.packageName) return
        if (cleanPkg.isNotEmpty()) {
            knownSessionPackages[sessionId] = cleanPkg
        }
        dspExecutor.execute {
            if (!currentConfig.globalEnabled) return@execute
            attachOrUpdateSessionInternal(sessionId, cleanPkg.ifEmpty { knownSessionPackages[sessionId] ?: "Reproductor externo" })
        }
    }

    fun onExternalAudioSessionClosed(context: Context, sessionId: Int, pkg: String?) {
        if (sessionId <= 0) return
        val appCtx = context.applicationContext
        if (pkg == appCtx.packageName) return
        knownSessionPackages.remove(sessionId)
        dspExecutor.execute {
            sessions.remove(sessionId)?.releaseAll()
        }
    }

    fun updateFromJson(context: Context, rawJson: String): String {
        val appCtx = context.applicationContext
        ensureInitialized(appCtx)
        val parsed = parseConfig(rawJson)
        val prevGlobal = currentConfig.globalEnabled
        currentConfig = parsed

        dspExecutor.execute {
            try {
                appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CONFIG, rawJson)
                    .apply()
            } catch (_: Exception) {
            }
            updatePlaybackCallbackRegistration(appCtx)
            if (!parsed.globalEnabled) {
                releaseAllSessionsInternal()
            } else {
                for ((sid, pkg) in knownSessionPackages) {
                    attachOrUpdateSessionInternal(sid, pkg)
                }
                reapplyAllSessionsInternal()
            }
            if (prevGlobal != parsed.globalEnabled) {
                PlaybackService.notifyGlobalModeChanged(appCtx, parsed.globalEnabled)
            }
        }
        return buildStatusJson(appCtx)
    }

    fun isGlobalActive(): Boolean = currentConfig.globalEnabled

    fun buildStatusJson(context: Context): String {
        ensureInitialized(context)
        val obj = JSONObject()
        val cfg = currentConfig
        obj.put("globalEnabled", cfg.globalEnabled)
        obj.put("dvcEnabled", cfg.dvcEnabled)
        obj.put("advancedTracking", cfg.advancedTracking)
        obj.put("apiDynamicsProcessing", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        obj.put("activeSessionsCount", sessions.size)
        val playersArr = JSONArray()
        sessions.values.map { it.packageName }.distinct().forEach { p ->
            playersArr.put(friendlyPlayerName(p))
        }
        obj.put("activePlayers", playersArr)
        return obj.toString()
    }

    private fun friendlyPlayerName(pkg: String): String {
        val lower = pkg.lowercase()
        return when {
            "spotify" in lower -> "Spotify"
            "youtube" in lower && "music" in lower -> "YouTube Music"
            "youtube" in lower -> "YouTube"
            "apple" in lower -> "Apple Music"
            "tidal" in lower -> "TIDAL"
            "deezer" in lower -> "Deezer"
            "amazon" in lower -> "Amazon Music"
            "vlc" in lower -> "VLC"
            "soundcloud" in lower -> "SoundCloud"
            pkg.contains('.') -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            else -> pkg.ifBlank { "Audio externo" }
        }
    }

    private fun releaseAllSessionsInternal() {
        val iter = sessions.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            entry.value.releaseAll()
            iter.remove()
        }
    }

    private fun reapplyAllSessionsInternal() {
        if (!currentConfig.globalEnabled) {
            releaseAllSessionsInternal()
            return
        }
        val dead = mutableListOf<Int>()
        for ((sid, holder) in sessions) {
            val ok = applyConfigToHolder(holder, currentConfig)
            if (!ok) dead.add(sid)
        }
        for (sid in dead) {
            sessions.remove(sid)?.releaseAll()
        }
    }

    private fun attachOrUpdateSessionInternal(sessionId: Int, pkgName: String) {
        if (sessionId <= 0) return
        val existing = sessions[sessionId]
        if (existing != null) {
            existing.packageName = pkgName
            if (!applyConfigToHolder(existing, currentConfig)) {
                sessions.remove(sessionId)?.releaseAll()
            }
            return
        }

        val holder = SessionAttachment(sessionId = sessionId, packageName = pkgName)
        var attached = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val dpConfig = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true,
                    10,
                    true,
                    2,
                    false,
                    0,
                    true
                ).build()
                val dp = DynamicsProcessing(0, sessionId, dpConfig)
                holder.dp = dp
                attached = applyDynamicsProcessing(dp, currentConfig)
            } catch (_: Exception) {
                holder.dp = null
            }
        }

        if (!attached) {
            try {
                val eq = Equalizer(0, sessionId)
                val le = LoudnessEnhancer(sessionId)
                holder.eq = eq
                holder.le = le
                attached = applyLegacyEffects(eq, le, currentConfig)
            } catch (_: Exception) {
                holder.releaseAll()
            }
        }

        if (attached) {
            sessions[sessionId] = holder
        } else {
            holder.releaseAll()
        }
    }

    private fun applyConfigToHolder(holder: SessionAttachment, cfg: DspSnapshot): Boolean {
        val dp = holder.dp
        if (dp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return applyDynamicsProcessing(dp, cfg)
        }
        val eq = holder.eq
        if (eq != null) {
            return applyLegacyEffects(eq, holder.le, cfg)
        }
        return false
    }

    private fun computeEffectiveBandGain(idx: Int, cfg: DspSnapshot): Float {
        var g = if (cfg.eqEnabled && idx in cfg.bands10.indices) cfg.bands10[idx] else 0f
        val bassDb = (cfg.bassPct.coerceIn(0f, 100f) / 100f) * 12f
        val trebDb = (cfg.treblePct.coerceIn(0f, 100f) / 100f) * 12f
        when (idx) {
            0 -> g += bassDb * 0.92f
            1 -> g += bassDb * 1.00f
            2 -> g += bassDb * 0.65f
            3 -> g += bassDb * 0.22f
            7 -> g += trebDb * 0.35f
            8 -> g += trebDb * 0.82f
            9 -> g += trebDb * 1.00f
        }
        return g.coerceIn(-18f, 18f)
    }

    private fun applyDynamicsProcessing(dp: DynamicsProcessing, cfg: DspSnapshot): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            val dvcHeadroom = if (cfg.dvcEnabled) -1.5f else 0f
            val totalInputDb = ((if (cfg.eqEnabled) cfg.preampDb else 0f) + dvcHeadroom).coerceIn(-18f, 15f)
            dp.setInputGainAllChannelsTo(totalInputDb)

            val cutoffs = floatArrayOf(
                45f, 90f, 180f, 355f, 710f, 1400f, 2800f, 5600f, 11200f, 20000f
            )
            for (i in 0 until 10) {
                val bandGain = computeEffectiveBandGain(i, cfg)
                val band = DynamicsProcessing.EqBand(cfg.eqEnabled || cfg.bassPct > 0f || cfg.treblePct > 0f, cutoffs[i], bandGain)
                dp.setPreEqBandAllChannelsTo(i, band)
            }

            // Compresor de 2 bandas con frecuencia de corte variable (20 Hz - 20.0 kHz)
            val splitHz = cfg.compFreqHz.coerceIn(80f, 20000f)
            val fullBand = splitHz >= 19500f
            val lowBand = DynamicsProcessing.MbcBand(
                cfg.compEnabled,
                if (fullBand) 20000f else splitHz,
                cfg.compAttackMs.coerceIn(0.1f, 500f),
                cfg.compReleaseMs.coerceIn(1f, 2000f),
                cfg.compRatio.coerceIn(1f, 30f),
                cfg.compThresholdDb.coerceIn(-90f, 0f),
                6.0f,
                -90f,
                1.0f,
                0f,
                cfg.compGainDb.coerceIn(-12f, 45f)
            )
            val highBand = DynamicsProcessing.MbcBand(
                cfg.compEnabled && !fullBand,
                20000f,
                cfg.compAttackMs.coerceIn(0.1f, 500f),
                cfg.compReleaseMs.coerceIn(1f, 2000f),
                1.0f,
                0.0f,
                0.0f,
                -90f,
                1.0f,
                0f,
                0.0f
            )
            dp.setMbcBandAllChannelsTo(0, lowBand)
            dp.setMbcBandAllChannelsTo(1, highBand)

            val limiter = DynamicsProcessing.Limiter(
                cfg.limiterEnabled,
                true,
                0,
                1.5f,
                35.0f,
                10.0f,
                cfg.masterCeilDb.coerceIn(-6.0f, -0.1f),
                0.0f
            )
            dp.setLimiterAllChannelsTo(limiter)
            dp.enabled = true
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun applyLegacyEffects(eq: Equalizer, le: LoudnessEnhancer?, cfg: DspSnapshot): Boolean {
        return try {
            val range = eq.bandLevelRange
            val minMb = range[0].toInt()
            val maxMb = range[1].toInt()
            val nBands = eq.numberOfBands.toInt()
            for (b in 0 until nBands) {
                val centerHz = eq.getCenterFreq(b.toShort()) / 1000f
                var bestIdx = 0
                var bestDist = Float.MAX_VALUE
                for (i in CONSOLE_FREQS.indices) {
                    val d = abs(CONSOLE_FREQS[i] - centerHz)
                    if (d < bestDist) {
                        bestDist = d
                        bestIdx = i
                    }
                }
                val targetMb = (computeEffectiveBandGain(bestIdx, cfg) * 100f).roundToInt().coerceIn(minMb, maxMb)
                eq.setBandLevel(b.toShort(), targetMb.toShort())
            }
            eq.enabled = cfg.eqEnabled || cfg.bassPct > 0f || cfg.treblePct > 0f

            if (le != null) {
                val preMb = ((if (cfg.eqEnabled) cfg.preampDb else 0f) * 100f).roundToInt().coerceIn(-1200, 1200)
                le.setTargetGain(max(0, preMb))
                le.enabled = preMb > 0
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parseConfig(rawJson: String): DspSnapshot {
        return try {
            val o = JSONObject(rawJson)
            val bands = FloatArray(10)
            val arr = o.optJSONArray("bands")
            if (arr != null) {
                for (i in 0 until min(10, arr.length())) {
                    bands[i] = arr.optDouble(i, 0.0).toFloat().coerceIn(-18f, 18f)
                }
            }
            DspSnapshot(
                globalEnabled = o.optBoolean("globalEnabled", false),
                dvcEnabled = o.optBoolean("dvcEnabled", true),
                advancedTracking = o.optBoolean("advancedTracking", true),
                eqEnabled = o.optBoolean("eqEnabled", true),
                limiterEnabled = o.optBoolean("limiterEnabled", true),
                compEnabled = o.optBoolean("compEnabled", false),
                preampDb = o.optDouble("preampDb", 0.0).toFloat(),
                bands10 = bands,
                bassPct = o.optDouble("bassPct", 0.0).toFloat(),
                treblePct = o.optDouble("treblePct", 0.0).toFloat(),
                compAttackMs = o.optDouble("compAttackMs", 2.5).toFloat(),
                compReleaseMs = o.optDouble("compReleaseMs", 17.0).toFloat(),
                compRatio = o.optDouble("compRatio", 8.7).toFloat(),
                compFreqHz = o.optDouble("compFreqHz", 20000.0).toFloat(),
                compThresholdDb = o.optDouble("compThresholdDb", -50.0).toFloat(),
                compGainDb = o.optDouble("compGainDb", 0.0).toFloat(),
                masterCeilDb = o.optDouble("masterCeilDb", -0.1).toFloat()
            )
        } catch (_: Exception) {
            DspSnapshot()
        }
    }
}
