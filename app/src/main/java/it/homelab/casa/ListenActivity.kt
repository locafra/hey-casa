package it.homelab.casa

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Activity trasparente aperta dal tile: tiene l'app in primo piano (serve per il microfono)
 * mostrando solo un riquadro in basso, lascia passare i tocchi e si chiude a fine comando.
 */
class ListenActivity : ComponentActivity() {

  private val app get() = application as CasaApp

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setShowWhenLocked(true) // da "Hey Meta" può arrivare a schermo bloccato
    setTurnScreenOn(true)
    window.addFlags(
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

    val d = resources.displayMetrics.density
    val bubble = TextView(this).apply {
      setTextColor(getColor(R.color.su_accento))
      textSize = 16f
      setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
      background = GradientDrawable().apply {
        setColor(getColor(R.color.accento))
        alpha = 240
        cornerRadius = 24 * d
      }
      text = "🎤 In ascolto…"
    }
    setContentView(
        FrameLayout(this).apply {
          addView(
              bubble,
              FrameLayout.LayoutParams(
                      FrameLayout.LayoutParams.WRAP_CONTENT,
                      FrameLayout.LayoutParams.WRAP_CONTENT,
                      Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                  .apply { bottomMargin = (96 * d).toInt() })
        })

    lifecycleScope.launch { app.assistant.status.collect { if (it.isNotBlank()) bubble.text = it } }

    if (savedInstanceState == null) {
      // Gira nello scope dell'app: se l'activity sparisce il comando arriva comunque in fondo
      app.scope.launch {
        if (!app.hasBluetoothPermission()) {
          app.log("Tile: apri l'app una volta per concedere i permessi")
        } else {
          app.initWearables()
          app.assistant.runCommand(intent.getStringExtra(EXTRA_SOURCE) ?: "tile")
          // Se il comando l'ha già avviato lo stream di Hey Meta, il riquadro resta finché non finisce
          // (serve l'app in primo piano per il microfono)
          while (app.assistant.busy) delay(200)
          delay(1_500) // lascia leggere la risposta
        }
        finish()
      }
    }
  }

  companion object {
    const val EXTRA_SOURCE = "it.homelab.casa.SOURCE"
  }
}
