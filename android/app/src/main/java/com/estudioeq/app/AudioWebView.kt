package com.estudioeq.app

import android.content.Context
import android.os.Build
import android.view.View
import android.webkit.WebView

class AudioWebView(context: Context) : WebView(context) {

    var uiStateListener: ((Boolean, Boolean) -> Unit)? = null
    private var realWindowVisible: Boolean = true
    private var realWindowFocused: Boolean = true

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Mantiene el subproceso C++ de WebAudio en la cgroup de máxima prioridad
            // incluso al bajar notificaciones, abrir PDFs o interactuar con el fondo de pantalla.
            setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        realWindowVisible = (visibility == View.VISIBLE)
        uiStateListener?.invoke(realWindowVisible, realWindowFocused)
        // Evita que WebViews OEM congelen HTMLAudioElement al cambiar de app,
        // mientras uiStateListener apaga el renderizado GPU/Canvas en JS al instante.
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (changedView === this) {
            realWindowVisible = (visibility == View.VISIBLE)
            uiStateListener?.invoke(realWindowVisible, realWindowFocused)
        }
        super.onVisibilityChanged(changedView, View.VISIBLE)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        realWindowFocused = hasWindowFocus
        uiStateListener?.invoke(realWindowVisible, realWindowFocused)
        super.onWindowFocusChanged(true)
    }
}
