package it.homelab.casa

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.voiceinvocations.isVoiceInvocationsIntent
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private val app get() = application as CasaApp
  private lateinit var status: TextView
  private lateinit var logView: TextView
  private lateinit var registerBtn: Button

  private val androidPermissions =
      registerForActivityResult(RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.BLUETOOTH_CONNECT] == true) {
          app.initWearables()
          observeWearables()
        } else {
          app.log("Permesso Bluetooth negato: senza non vedo gli occhiali")
        }
      }

  private val glassesPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val st = result.getOrDefault(PermissionStatus.Denied)
        app.log("Permesso microfono occhiali: ${if (st is PermissionStatus.Granted) "concesso" else "negato"}")
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContentView(buildUi())

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) { app.log.collect { logView.text = it } }
    }
    // Le richieste di ascolto arrivano dall'app (Hey Meta) o dal pulsante
    lifecycleScope.launch { app.triggers.collect { app.assistant.runCommand(it) } }

    val missing =
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO).filter {
          checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
    if (missing.isEmpty()) observeWearables() else androidPermissions.launch(missing.toTypedArray())

    handleVoiceIntent(intent)
    logMetaAiVersion()
  }

  /** La 1.0 dell'SDK vuole Meta AI ≥ V290. */
  private fun logMetaAiVersion() {
    val v = runCatching { packageManager.getPackageInfo("com.facebook.stella", 0).versionName }
        .getOrElse { "non installata" }
    app.log("App Meta AI: $v")
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleVoiceIntent(intent)
  }

  /**
   * Avvio da "Hey Meta": l'ascolto vero parte quando arriva l'invocazione sullo
   * stream (CasaApp); qui la registriamo solo nel log.
   */
  private fun handleVoiceIntent(intent: Intent?) {
    if (intent == null) return
    if (isVoiceInvocationsIntent(intent) && !intent.getBooleanExtra(HANDLED, false)) {
      // L'avvio vocale arriva anche come intent: ascolto subito (il doppione dal canale viene
      // scartato perché l'assistente è già occupato). Anche a schermo bloccato.
      intent.putExtra(HANDLED, true)
      app.log("Aperta da Hey Meta")
      if (app.hasBluetoothPermission()) {
        // Come il tile: solo il riquadro trasparente, la schermata principale va in secondo piano
        startActivity(
            Intent(this, ListenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ListenActivity.EXTRA_SOURCE, "hey-meta-intent"))
        moveTaskToBack(true)
      } else {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        app.trigger("hey-meta-intent")
      }
    }
    if (intent.getBooleanExtra(EXTRA_LISTEN, false)) {
      intent.removeExtra(EXTRA_LISTEN) // non ripetere l'ascolto se l'activity viene ricreata
      app.trigger("tile")
    }
  }

  companion object {
    const val EXTRA_LISTEN = "it.homelab.casa.LISTEN"
    private const val HANDLED = "it.homelab.casa.HANDLED"
  }

  private var observing = false

  private fun observeWearables() {
    if (observing || !app.hasBluetoothPermission()) return
    app.initWearables()
    observing = true
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        Wearables.registrationState.collect { st ->
          status.text = "Meta AI: ${stateLabel(st)}"
          registerBtn.text =
              if (st == RegistrationState.REGISTERED) "Scollega da Meta AI" else "Collega a Meta AI"
        }
      }
    }
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        Wearables.devices.collect { app.log("Occhiali visibili: ${it.size}") }
      }
    }
  }

  private fun stateLabel(st: RegistrationState) =
      when (st) {
        RegistrationState.REGISTERED -> "collegata ✓"
        RegistrationState.REGISTERING -> "collegamento in corso…"
        RegistrationState.AVAILABLE -> "da collegare"
        RegistrationState.UNREGISTERING -> "scollegamento…"
        RegistrationState.UNAVAILABLE -> "non disponibile (app Meta AI / Developer Mode?)"
      }

  // --- UI in codice, niente XML: tema chiaro/scuro dai colori in res/values -------------------

  private lateinit var column: LinearLayout
  private val d get() = resources.displayMetrics.density
  private fun dp(v: Int) = (v * d).toInt()

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    fitWidth()
  }

  /** Sul display interno del pieghevole la colonna resta larga al massimo 600 dp, centrata. */
  private fun fitWidth() {
    val side = maxOf(dp(16), (dp(resources.configuration.screenWidthDp) - dp(600)) / 2)
    column.setPadding(side, dp(20), side, dp(24))
  }

  private fun rounded(fill: Int, stroke: Int? = null, radius: Int = 14) =
      GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius * d
        if (stroke != null) setStroke(dp(1), stroke)
      }

  private fun label(t: String, size: Float = 14f, bold: Boolean = false, dim: Boolean = false) =
      TextView(this).apply {
        text = t
        textSize = size
        setTextColor(getColor(R.color.testo))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (dim) alpha = 0.75f
      }

  private fun button(t: String, primary: Boolean = false, onClick: () -> Unit) =
      Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 15f
        stateListAnimator = null
        minHeight = dp(48)
        val accent = getColor(R.color.accento)
        setTextColor(if (primary) getColor(R.color.su_accento) else accent)
        val shape = if (primary) rounded(accent) else rounded(getColor(R.color.carta), accent)
        background = RippleDrawable(ColorStateList.valueOf(0x33808080), shape, shape)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
      }

  private fun field(value: String, password: Boolean = false, uri: Boolean = false) =
      EditText(this).apply {
        setText(value)
        isSingleLine = true
        textSize = 15f
        setTextColor(getColor(R.color.testo))
        setHintTextColor(getColor(R.color.testo) and 0x00FFFFFF or 0x80000000.toInt())
        inputType =
            when {
              password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
              uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
              else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
      }

  /** Una scheda col titolo: fondo "carta", bordo sottile, angoli tondi. */
  private fun card(title: String, fill: LinearLayout.() -> Unit) =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(getColor(R.color.carta), getColor(R.color.bordo), 20)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) }
        addView(label(title, 17f, bold = true))
        fill()
      }

  private fun buildUi(): View {
    column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fitWidth()

    column.addView(LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      addView(ImageView(this@MainActivity).apply {
        setImageResource(R.drawable.logo)
        background = rounded(getColor(R.color.icona_fondo), getColor(R.color.bordo), 16)
        setPadding(dp(6), dp(6), dp(6), dp(6))
      }, LinearLayout.LayoutParams(dp(64), dp(64)))
      addView(LinearLayout(this@MainActivity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), 0, 0, 0)
        addView(label(getString(R.string.app_name), 26f, bold = true))
        addView(label("Occhiali Meta → Home Assistant", dim = true))
      })
    })

    column.addView(button("🎤  Prova comando", primary = true) { app.trigger("pulsante") }.apply {
      textSize = 17f
      minHeight = dp(56)
      (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
    })

    column.addView(card("Occhiali") {
      status = label("Meta AI: …", dim = true).apply { setPadding(0, dp(4), 0, dp(4)) }
      addView(status)
      registerBtn = button("Collega a Meta AI", primary = true) {
        if (Wearables.registrationState.value == RegistrationState.REGISTERED)
            Wearables.startUnregistration(this@MainActivity)
        else Wearables.startRegistration(this@MainActivity)
      }
      addView(registerBtn)
      addView(button("Permesso microfono occhiali") { glassesPermission.launch(Permission.MICROPHONE) })
      addView(button("Aggiorna Toolkit sugli occhiali") {
        Wearables.openDATGlassesAppUpdate(this@MainActivity)
            .onSuccess { app.log("Aperta la pagina di aggiornamento in Meta AI") }
            .onFailure { error, _ -> app.log("Aggiornamento Toolkit: ${error.description}") }
      })
      addView(button("Aggiorna firmware occhiali") {
        Wearables.openFirmwareUpdate(this@MainActivity)
            .onSuccess { app.log("Aperta la pagina firmware in Meta AI") }
            .onFailure { error, _ -> app.log("Firmware: ${error.description}") }
      })
      addView(button("Riavvia ascolto \"Hey Meta\"") { app.restartVoiceInvocations("pulsante") })
    })

    val a = app.assistant
    column.addView(card("Home Assistant") {
      fun title(t: String) = addView(label(t, 13f, dim = true).apply { setPadding(0, dp(12), 0, 0) })
      title("Indirizzo in casa (provato per primo)")
      val url = field(a.haUrl, uri = true)
      addView(url)
      title("Indirizzo da fuori casa (ripiego)")
      val urlRemote = field(a.haUrlRemote, uri = true).apply { hint = "https://…" }
      addView(urlRemote)
      title("Token di accesso a lunga durata")
      val savedHint = "•••• salvato (lascia vuoto per tenerlo)"
      val token = field("", password = true).apply {
        hint = if (a.haToken.isBlank()) "incollalo qui" else savedHint
      }
      addView(token)
      title("Agente di conversazione")
      val agent = field(a.agentId)
      addView(agent)
      title("Agente LLM se Home Assistant non capisce (vuoto = nessuno)")
      val llmAgent = field(a.llmAgentId).apply { hint = "conversation.…" }
      addView(llmAgent)
      val glasses = CheckBox(this@MainActivity).apply {
        text = "Trascrizione sugli occhiali (Speech, sperimentale)"
        setTextColor(getColor(R.color.testo))
        isChecked = a.useGlassesSpeech
        setPadding(0, dp(12), 0, dp(4))
      }
      addView(glasses)
      addView(button("Salva", primary = true) {
        a.haUrl = url.text.toString()
        a.haUrlRemote = urlRemote.text.toString()
        if (token.text.isNotBlank()) a.haToken = token.text.toString()
        a.agentId = agent.text.toString()
        a.llmAgentId = llmAgent.text.toString()
        a.useGlassesSpeech = glasses.isChecked
        token.setText("")
        token.hint = savedHint
        app.log("Impostazioni salvate")
      })
      addView(button("Prova Home Assistant (\"che ore sono\")") {
        lifecycleScope.launch {
          val r = runCatching { a.askHomeAssistant("che ore sono") }
          app.log("Test HA: " + r.fold({ it }, { "errore ${it.message}" }))
        }
      })
    })

    column.addView(card("Registro") {
      logView = label("", 11f).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(0, dp(8), 0, 0)
      }
      addView(logView)
    })

    column.addView(label("${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}", 13f, dim = true).apply {
      gravity = Gravity.CENTER
      setPadding(0, dp(20), 0, 0)
    })

    return ScrollView(this).apply {
      setBackgroundColor(getColor(R.color.fondo))
      addView(column)
      // barre di sistema, fotocamera e tastiera: il contenuto sta dentro, il fondo arriva ai bordi
      setOnApplyWindowInsetsListener { v, ins ->
        val b = ins.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        WindowInsets.CONSUMED
      }
    }
  }
}
