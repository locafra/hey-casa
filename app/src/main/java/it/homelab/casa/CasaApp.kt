package it.homelab.casa

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.voiceinvocations.VoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.startVoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.types.SessionState
import com.meta.wearable.dat.core.voiceinvocations.types.VoiceInvocationError
import com.meta.wearable.dat.core.voiceinvocations.types.actions.LaunchApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stato condiviso dell'app: SDK Meta, stream "Hey Meta", log e impostazioni. */
class CasaApp : Application() {

  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private val _log = MutableStateFlow("")
  val log = _log.asStateFlow()

  /** Emette quando bisogna ascoltare un comando (Hey Meta, pulsante, intent). */
  private val _triggers = MutableSharedFlow<String>(extraBufferCapacity = 4)
  val triggers = _triggers.asSharedFlow()

  private var wearablesReady = false
  private var voiceStream: VoiceInvocationsStream? = null

  lateinit var assistant: Assistant
    private set

  override fun onCreate() {
    super.onCreate()
    instance = this
    assistant = Assistant(this)
    if (hasBluetoothPermission()) initWearables()
  }

  fun hasBluetoothPermission(): Boolean =
      checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
          PackageManager.PERMISSION_GRANTED

  /** Inizializza l'SDK (serve il permesso Bluetooth) e apre lo stream delle voice invocations. */
  fun initWearables() {
    if (wearablesReady) return
    Wearables.initialize(this)
        .onSuccess {
          wearablesReady = true
          log(getString(R.string.log_sdk_ready, Wearables.isDevMode.toString()))
          restartVoiceInvocations(getString(R.string.reason_start))
          watchDevicesForVoice()
        }
        .onFailure { error, _ -> log(getString(R.string.log_sdk_failed, error.description)) }
  }

  private var voiceJob: Job? = null
  private var voiceRetry: Job? = null
  private var voiceBackoffMs = VOICE_BACKOFF_MIN_MS

  /**
   * Apre (o riapre) lo stream delle voice invocations. Lo stream non riprova da solo: se il
   * canale verso gli occhiali cade (CHANNEL_ERROR, tipico se il componente DAT sugli occhiali
   * non era pronto) lo chiudiamo e lo riapriamo con attese crescenti.
   */
  fun restartVoiceInvocations(reason: String) {
    if (!wearablesReady) return
    voiceRetry?.cancel()
    voiceJob?.cancel()
    voiceStream?.close()
    voiceJob =
        scope.launch {
          // Meta AI instrada le invocazioni solo a un'app registrata: aprire il canale prima
          // della registrazione lo fa cadere con la richiesta ancora in mano a Meta AI
          // (→ "Something went wrong"). Come nel sample BirdSpotter, si aspetta REGISTERED.
          if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
            log(getString(R.string.log_hm_wait))
            Wearables.registrationState.first { it == RegistrationState.REGISTERED }
          }
          log(getString(R.string.log_hm_open, reason))
          val stream = Wearables.startVoiceInvocationsStream(AutoDeviceSelector())
          voiceStream = stream
          launch {
            stream.state.collect {
              log("Hey Meta: stream $it")
              // Restato su senza errori per un minuto: il canale è buono, azzero l'attesa
              if (it == SessionState.STARTED) {
                delay(60_000)
                voiceBackoffMs = VOICE_BACKOFF_MIN_MS
              }
            }
          }
          launch {
            stream.errors.collect { err ->
              log(getString(R.string.log_hm_error, err.name, err.description))
              if (err in RETRYABLE) scheduleVoiceRetry()
            }
          }
          launch {
            stream.invocations.collect { invocation ->
              when (invocation) {
                is LaunchApp -> {
                  log(getString(R.string.log_hm_received))
                  voiceBackoffMs = VOICE_BACKOFF_MIN_MS
                  invocation.responseHandle.sendSuccess(null)
                  // Meta AI porta l'app in primo piano: l'ascolto parte subito, senza
                  // aspettare che l'activity sia pronta a ricevere il trigger
                  scope.launch { assistant.runCommand("hey-meta") }
                }
                else -> {
                  log(getString(R.string.log_hm_unhandled, invocation::class.simpleName))
                }
              }
            }
          }
        }
  }

  private fun scheduleVoiceRetry() {
    if (voiceRetry?.isActive == true) return
    val wait = voiceBackoffMs
    voiceBackoffMs = (voiceBackoffMs * 2).coerceAtMost(VOICE_BACKOFF_MAX_MS)
    log(getString(R.string.log_hm_retry, "%.1f".format(wait / 1000.0)))
    voiceRetry = scope.launch {
      delay(wait)
      restartVoiceInvocations(getString(R.string.reason_retry))
    }
  }

  /** Quando gli occhiali compaiono (accesi, ricollegati) riapre lo stream. */
  private fun watchDevicesForVoice() {
    scope.launch {
      var hadDevice = Wearables.devices.value.isNotEmpty()
      Wearables.devices.collect { devices ->
        val has = devices.isNotEmpty()
        if (has && !hadDevice) {
          delay(5_000) // lascia stabilizzare il collegamento
          voiceBackoffMs = VOICE_BACKOFF_MIN_MS
          restartVoiceInvocations(getString(R.string.reason_glasses))
        }
        hadDevice = has
      }
    }
  }

  fun trigger(source: String) {
    _triggers.tryEmit(source)
  }

  fun log(msg: String) {
    Log.i(TAG, msg)
    val ts = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
    _log.value = ("$ts  $msg\n" + _log.value).take(8000)
  }

  val prefs by lazy { getSharedPreferences("casa", Context.MODE_PRIVATE) }

  companion object {
    const val TAG = "HeyCasa"
    private const val VOICE_BACKOFF_MIN_MS = 500L
    private const val VOICE_BACKOFF_MAX_MS = 30_000L
    private val RETRYABLE =
        setOf(
            VoiceInvocationError.CHANNEL_ERROR,
            VoiceInvocationError.CHANNEL_NOT_CONNECTED,
            VoiceInvocationError.FAIL_TO_SEND_INIT_REQUEST,
            VoiceInvocationError.INIT_REQUEST_ERROR,
            VoiceInvocationError.INVALID_SESSION_STATE,
        )
    lateinit var instance: CasaApp
      private set
  }
}
