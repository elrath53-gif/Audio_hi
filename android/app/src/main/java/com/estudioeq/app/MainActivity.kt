package com.estudioeq.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var sharedWebView: AudioWebView? = null
        private var engineInitialized = false
    }

    private lateinit var container: FrameLayout
    private lateinit var webView: AudioWebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var mediaObserverRegistered = false
    private var audioCallbackRegistered = false
    private var pageLoaded = false
    private var lastTracksJson: String = ""
    private var activityResumed = false
    private var windowFocused = true
    private var lastPushedUiState: String = ""

    private var pendingJackReason: String = "plug"
    private val jackDebounce = Runnable {
        pushJackStateNow(pendingJackReason)
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            scheduleJackPush("plug")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            scheduleJackPush("unplug")
        }
    }

    private val scanDebounce = Runnable {
        scanAndPush("observer")
    }

    private val mediaObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            mainHandler.removeCallbacks(scanDebounce)
            mainHandler.postDelayed(scanDebounce, 850L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val granted = hasAudioPermission()
        pushPermissionState(granted)
        if (granted) {
            ensureMediaObserver()
            scanAndPush("permission")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GlobalAudioEngine.ensureInitialized(applicationContext)

        window.statusBarColor = Color.parseColor("#03020A")
        window.navigationBarColor = Color.parseColor("#060412")

        container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#03020A"))
        }
        setContentView(container)

        val existing = sharedWebView
        if (existing != null) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            webView = existing
            pageLoaded = engineInitialized
        } else {
            // Creado con applicationContext para sobrevivir al cambiar de apps, abrir PDFs o tocar el fondo
            webView = AudioWebView(applicationContext).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.parseColor("#04020C"))
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    allowFileAccess = false
                    allowContentAccess = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
            }
            sharedWebView = webView
        }

        webView.uiStateListener = { vis, foc ->
            windowFocused = foc
            pushNativeUiState(activityResumed && vis, activityResumed && foc)
        }

        // Re-registra el puente apuntando a la instancia activa
        webView.removeJavascriptInterface("AndroidBridge")
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        if (!engineInitialized) {
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url ?: return null
                    if (url.scheme == "https" && url.host == "estudiomaster.local") {
                        val path = url.path ?: "/"
                        if (path.startsWith("/media/")) {
                            val id = path.removePrefix("/media/").toLongOrNull() ?: return null
                            return MusicScanner.openMediaResponse(
                                applicationContext,
                                id,
                                request.requestHeaders ?: emptyMap()
                            )
                        }
                        return openAssetResponse(path)
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pageLoaded = true
                    engineInitialized = true
                    webView.resumeTimers()
                    lastPushedUiState = ""
                    pushNativeUiState(activityResumed, activityResumed && windowFocused)
                    pushJackState("init")
                    if (hasAudioPermission()) {
                        ensureMediaObserver()
                        scanAndPush("init")
                    } else {
                        pushPermissionState(false)
                        requestRequiredPermissions()
                    }
                }
            }
        }

        container.addView(webView)
        webView.resumeTimers()
        ensureAudioDeviceCallback()

        PlaybackService.commandHandler = { cmd, arg ->
            mainHandler.post {
                val safeCmd = JSONObject.quote(cmd)
                webView.evaluateJavascript("window.__nativeCmd && window.__nativeCmd($safeCmd, $arg);", null)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        if (!engineInitialized) {
            webView.loadUrl("https://estudiomaster.local/index.html")
        } else if (hasAudioPermission()) {
            ensureMediaObserver()
            scanAndPush("observer")
        }
    }

    private fun pushNativeUiState(visible: Boolean, focused: Boolean) {
        val sig = "$visible|$focused"
        if (sig == lastPushedUiState) return
        lastPushedUiState = sig
        mainHandler.post {
            if (!pageLoaded) return@post
            webView.evaluateJavascript(
                "window.__onNativeUiState && window.__onNativeUiState($visible, $focused);",
                null
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus
        // Al bajar la cortina de notificaciones (hasFocus = false), JS detiene Canvas/GPU al instante
        // para que el blur del sistema Android jamás compita con el hilo C++ de audio.
        pushNativeUiState(activityResumed, activityResumed && hasFocus)
    }

    override fun onPause() {
        activityResumed = false
        pushNativeUiState(false, false)
        super.onPause()
        webView.resumeTimers()
    }

    override fun onStop() {
        activityResumed = false
        pushNativeUiState(false, false)
        super.onStop()
        webView.resumeTimers()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        webView.resumeTimers()
        pushNativeUiState(true, hasWindowFocus())
        ensureAudioDeviceCallback()
        if (pageLoaded) {
            scheduleJackPush("resume")
            if (hasAudioPermission()) {
                ensureMediaObserver()
                if (lastTracksJson.isEmpty()) {
                    scanAndPush("init")
                }
            }
        }
    }

    private fun openAssetResponse(rawPath: String): WebResourceResponse {
        val clean = rawPath.trimStart('/').ifEmpty { "index.html" }
        val (mime, textEncoding) = when {
            clean.endsWith(".html") -> "text/html" to "UTF-8"
            clean.endsWith(".js") -> "text/javascript" to "UTF-8"
            clean.endsWith(".css") -> "text/css" to "UTF-8"
            clean.endsWith(".json") -> "application/json" to "UTF-8"
            clean.endsWith(".svg") -> "image/svg+xml" to "UTF-8"
            clean.endsWith(".wav") -> "audio/wav" to null
            clean.endsWith(".ttf") -> "font/ttf" to null
            clean.endsWith(".otf") -> "font/otf" to null
            clean.endsWith(".woff2") -> "font/woff2" to null
            clean.endsWith(".woff") -> "font/woff" to null
            clean.endsWith(".png") -> "image/png" to null
            clean.endsWith(".webp") -> "image/webp" to null
            else -> "application/octet-stream" to null
        }
        return try {
            // ACCESS_RANDOM aprovecha el mapeo directo mmap zero-copy sobre archivos STORE (0% compresión) del APK
            val stream = applicationContext.assets.open(
                clean,
                android.content.res.AssetManager.ACCESS_RANDOM
            )
            WebResourceResponse(
                mime,
                textEncoding,
                200,
                "OK",
                mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Cache-Control" to "public, max-age=31536000, immutable"
                ),
                stream
            )
        } catch (_: Exception) {
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                404,
                "Not Found",
                mapOf("Access-Control-Allow-Origin" to "*"),
                ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    private fun hasAudioPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun ensureMediaObserver() {
        if (mediaObserverRegistered) return
        try {
            applicationContext.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaObserver
            )
            mediaObserverRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun ensureAudioDeviceCallback() {
        if (audioCallbackRegistered) return
        try {
            val am = applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
            audioCallbackRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun inspectJackHardwareJson(): String {
        val obj = JSONObject()
        try {
            val am = applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val nativeRate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
            val framesPerBuf = am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
            val outputs = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()

            var wiredDev: AudioDeviceInfo? = null
            var wiredPriority = 0
            var activeFallback = "speaker"

            for (dev in outputs) {
                when (dev.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_LINE_ANALOG,
                    AudioDeviceInfo.TYPE_AUX_LINE -> {
                        if (wiredPriority < 3) {
                            wiredDev = dev
                            wiredPriority = 3
                        }
                    }
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY,
                    AudioDeviceInfo.TYPE_LINE_DIGITAL -> {
                        if (wiredPriority < 2) {
                            wiredDev = dev
                            wiredPriority = 2
                        }
                    }
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                        activeFallback = "bluetooth"
                    }
                }
            }

            if (wiredDev != null) {
                val isAnalogJack = wiredPriority == 3
                val rawRates = wiredDev.sampleRates
                val explicitRates = rawRates != null && rawRates.isNotEmpty()
                val cleanRates = if (explicitRates) {
                    rawRates!!.filter { it in 44100..384000 }.distinct().sorted()
                } else {
                    listOf(nativeRate).distinct()
                }
                val maxRate = if (isAnalogJack) nativeRate else (cleanRates.maxOrNull() ?: nativeRate)

                val rawEncs = wiredDev.encodings ?: IntArray(0)
                var maxBits = 24
                var floatPcm = true
                for (enc in rawEncs) {
                    if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
                        maxBits = 32
                        floatPcm = true
                    } else if (enc == 22 && maxBits < 32) {
                        maxBits = 32
                    } else if (enc == 21 && maxBits < 24) {
                        maxBits = 24
                    }
                }

                val prodName = wiredDev.productName?.toString()?.trim().orEmpty()
                val label = if (prodName.isNotEmpty() && !prodName.equals(Build.MODEL, ignoreCase = true)) {
                    prodName
                } else if (isAnalogJack) {
                    "Puerto Jack 3.5 mm"
                } else {
                    "DAC USB-C / Jack"
                }

                val ratesArr = JSONArray()
                cleanRates.forEach { ratesArr.put(it) }

                obj.put("jackConnected", true)
                obj.put("portType", if (isAnalogJack) "jack_35mm" else "usb_dac_jack")
                obj.put("deviceName", label)
                obj.put("nativeRate", nativeRate)
                obj.put("framesPerBuffer", framesPerBuf)
                obj.put("arbitraryRates", false)
                obj.put("maxSampleRate", maxRate)
                obj.put("supportedRates", ratesArr)
                obj.put("bitDepth", maxBits)
                obj.put("floatPcm", floatPcm)
            } else {
                val ratesArr = JSONArray().apply { put(nativeRate) }
                obj.put("jackConnected", false)
                obj.put("portType", activeFallback)
                obj.put("deviceName", if (activeFallback == "bluetooth") "Audio Bluetooth" else "Altavoz del teléfono")
                obj.put("nativeRate", nativeRate)
                obj.put("framesPerBuffer", framesPerBuf)
                obj.put("arbitraryRates", false)
                obj.put("maxSampleRate", nativeRate)
                obj.put("supportedRates", ratesArr)
                obj.put("bitDepth", 24)
                obj.put("floatPcm", true)
            }
        } catch (_: Exception) {
            obj.put("jackConnected", false)
            obj.put("portType", "speaker")
            obj.put("nativeRate", 48000)
            obj.put("maxSampleRate", 48000)
            obj.put("bitDepth", 24)
        }
        return obj.toString()
    }

    private fun scheduleJackPush(reason: String) {
        pendingJackReason = reason
        mainHandler.removeCallbacks(jackDebounce)
        mainHandler.postDelayed(jackDebounce, 380L)
    }

    private fun pushJackState(reason: String) {
        pushJackStateNow(reason)
    }

    private fun pushJackStateNow(reason: String) {
        mainHandler.post {
            if (!pageLoaded) return@post
            val json = inspectJackHardwareJson()
            val quotedJson = JSONObject.quote(json)
            val quotedReason = JSONObject.quote(reason)
            webView.evaluateJavascript(
                "window.__onNativeJack && window.__onNativeJack($quotedJson, $quotedReason);",
                null
            )
        }
    }

    private fun pushPermissionState(granted: Boolean) {
        mainHandler.post {
            if (!pageLoaded) return@post
            webView.evaluateJavascript("window.__onNativePermission && window.__onNativePermission($granted);", null)
        }
    }

    private fun scanAndPush(reason: String) {
        if (!hasAudioPermission()) {
            pushPermissionState(false)
            return
        }
        val appCtx = applicationContext
        ioExecutor.execute {
            val json = MusicScanner.queryTracksJson(appCtx)
            if (reason == "observer" && json == lastTracksJson) {
                return@execute
            }
            lastTracksJson = json
            val quotedJson = JSONObject.quote(json)
            val quotedReason = JSONObject.quote(reason)
            mainHandler.post {
                if (!pageLoaded) return@post
                webView.evaluateJavascript(
                    "window.__onNativeTracks && window.__onNativeTracks($quotedJson, $quotedReason);",
                    null
                )
            }
        }
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun hasMusicPermission(): Boolean = hasAudioPermission()

        @JavascriptInterface
        fun requestMusicPermission() {
            mainHandler.post { requestRequiredPermissions() }
        }

        @JavascriptInterface
        fun rescanMusic() {
            scanAndPush("manual")
        }

        @JavascriptInterface
        fun saveState(json: String): Boolean {
            val appCtx = applicationContext
            ioExecutor.execute {
                NativeStore.save(appCtx, json)
            }
            return true
        }

        @JavascriptInterface
        fun loadState(): String {
            return NativeStore.load(applicationContext)
        }

        @JavascriptInterface
        fun isInPhoneCall(): Boolean {
            val am = applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val mode = am.mode
            return mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_RINGTONE
        }

        @JavascriptInterface
        fun updatePlaybackState(
            title: String,
            artist: String,
            isPlaying: Boolean,
            positionMs: Long,
            durationMs: Long
        ) {
            PlaybackService.updateStateFast(
                applicationContext,
                title,
                artist,
                isPlaying,
                positionMs,
                durationMs
            )
        }

        @JavascriptInterface
        fun syncGlobalDsp(configJson: String): String {
            return GlobalAudioEngine.updateFromJson(applicationContext, configJson)
        }

        @JavascriptInterface
        fun getGlobalDspStatus(): String {
            return GlobalAudioEngine.buildStatusJson(applicationContext)
        }

        @JavascriptInterface
        fun getHardwareVolumePct(): Int {
            return try {
                val am = applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 100
                val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val curV = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                ((curV * 100.0) / maxV).roundToInt().coerceIn(0, 100)
            } catch (_: Exception) {
                100
            }
        }

        @JavascriptInterface
        fun setHardwareVolumePct(pct: Int) {
            val cleanPct = pct.coerceIn(0, 100)
            ioExecutor.execute {
                try {
                    val am = applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@execute
                    val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val target = ((cleanPct / 100.0) * maxV).roundToInt().coerceIn(0, maxV)
                    if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != target) {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    }
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun vibrate(ms: Long) {
            val dur = ms.coerceIn(5L, 250L)
            ioExecutor.execute {
                try {
                    val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val vm = applicationContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                        vm?.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    }
                    if (vibrator?.hasVibrator() == true) {
                        vibrator.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun isIgnoringBatteryOptimizations(): Boolean {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
            return pm.isIgnoringBatteryOptimizations(packageName)
        }

        @JavascriptInterface
        @SuppressLint("BatteryLife")
        fun openBatterySettings() {
            mainHandler.post {
                try {
                    val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
            }
        }

        @JavascriptInterface
        fun getJackHiResInfo(): String {
            return inspectJackHardwareJson()
        }

        @JavascriptInterface
        fun configureJackHiRes(
            @Suppress("UNUSED_PARAMETER") enable: Boolean,
            @Suppress("UNUSED_PARAMETER") preferredRate: Int
        ): String {
            return inspectJackHardwareJson()
        }

        @JavascriptInterface
        fun setWindowColors(statusHex: String, navHex: String) {
            mainHandler.post {
                try {
                    val sc = Color.parseColor(statusHex)
                    val nc = Color.parseColor(navHex)
                    window.statusBarColor = sc
                    window.navigationBarColor = nc
                    container.setBackgroundColor(sc)
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onDestroy() {
        if (mediaObserverRegistered) {
            try {
                applicationContext.contentResolver.unregisterContentObserver(mediaObserver)
            } catch (_: Exception) {
            }
            mediaObserverRegistered = false
        }
        if (audioCallbackRegistered) {
            try {
                val am = applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.unregisterAudioDeviceCallback(audioDeviceCallback)
            } catch (_: Exception) {
            }
            audioCallbackRegistered = false
        }
        webView.uiStateListener = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        super.onDestroy()
    }
}
