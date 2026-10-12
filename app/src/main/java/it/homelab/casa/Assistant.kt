package it.homelab.casa

import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.speech.addSpeech
import com.meta.wearable.dat.speech.removeSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Un giro completo: ascolta un comando, lo manda all'agente conversation di Home Assistant e
 * legge la risposta sugli occhiali.
 */
class Assistant(private val app: CasaApp) {

  private val audio = app.getSystemService(AudioManager::class.java)
  private var tts: TextToSpeech? = null
  private var ttsReady = false
  /** Fase corrente, mostrata dal riquadro del tile. */
  val status = MutableStateFlow("")

  @Volatile var busy = false
    private set

  init {
    tts = TextToSpeech(app) { status ->
      ttsReady = status == TextToSpeech.SUCCESS
      if (ttsReady) tts?.language = Locale.forLanguageTag(app.getString(R.string.lang_tag))
      app.log(app.getString(if (ttsReady) R.string.log_tts_ready else R.string.log_tts_unavailable))
    }
  }

  // --- impostazioni ---------------------------------------------------------------------------

  /** URL provato per primo, con timeout breve (rete di casa). */
  var haUrl: String
    get() = app.prefs.getString("ha_url", DEFAULT_URL)!!
    set(v) = app.prefs.edit().putString("ha_url", v.trim().trimEnd('/')).apply()

  /** URL di ripiego quando quello di casa non risponde (fuori casa). */
  var haUrlRemote: String
    get() = app.prefs.getString("ha_url_remote", DEFAULT_URL_REMOTE)!!
    set(v) = app.prefs.edit().putString("ha_url_remote", v.trim().trimEnd('/')).apply()

  var haToken: String
    get() = app.prefs.getString("ha_token", "")!!
    set(v) = app.prefs.edit().putString("ha_token", v.trim()).apply()

  var agentId: String
    get() = app.prefs.getString("agent_id", DEFAULT_AGENT)!!
    set(v) = app.prefs.edit().putString("agent_id", v.trim()).apply()

  /** Agente LLM usato quando quello di HA non capisce la frase (vuoto = nessun ripiego). */
  var llmAgentId: String
    get() = app.prefs.getString("llm_agent_id", DEFAULT_LLM_AGENT)!!
    set(v) = app.prefs.edit().putString("llm_agent_id", v.trim()).apply()

  /** true = trascrizione sugli occhiali (Speech DAT), false = riconoscimento del telefono. */
  var useGlassesSpeech: Boolean
    get() = app.prefs.getBoolean("glasses_speech", true)
    set(v) = app.prefs.edit().putBoolean("glasses_speech", v).apply()

  // --- giro completo --------------------------------------------------------------------------

  suspend fun runCommand(source: String) {
    if (busy) {
      app.log(app.getString(R.string.log_busy, source))
      return
    }
    busy = true
    try {
      app.log(app.getString(R.string.log_listen_start, source))
      status.value = app.getString(R.string.st_listening)
      if (haToken.isBlank()) {
        speak(app.getString(R.string.say_no_token))
        return
      }
      beep()
      var text: String? = null
      if (useGlassesSpeech) text = listenOnGlasses()
      if (text == null) text = listenOnPhone()
      if (text.isNullOrBlank()) {
        status.value = app.getString(R.string.st_not_understood)
        speak(app.getString(R.string.say_not_understood))
        return
      }
      app.log(app.getString(R.string.log_command, text))
      status.value = "“$text”"
      val reply =
          try {
            askHomeAssistant(text)
          } catch (e: Exception) {
            app.log(app.getString(R.string.log_ha_error, e.message))
            app.getString(R.string.say_ha_down)
          }
      app.log(app.getString(R.string.log_reply, reply))
      status.value = reply
      speak(reply)
    } finally {
      busy = false
    }
  }

  // --- ascolto sugli occhiali (Speech DAT, sperimentale) --------------------------------------

  private suspend fun listenOnGlasses(): String? {
    if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
      app.log(app.getString(R.string.log_g_unregistered))
      return null
    }
    val perm = Wearables.checkPermissionStatus(Permission.MICROPHONE).getOrNull()
    if (perm !is PermissionStatus.Granted) {
      app.log(app.getString(R.string.log_g_noperm))
      return null
    }
    val created = Wearables.createSession(AutoDeviceSelector())
    val session: DeviceSession =
        created.getOrNull()
            ?: run {
              app.log(app.getString(R.string.log_g_nosession, created.errorOrNull()?.description))
              return null
            }
    val errorsJob =
        app.scope.launch { session.errors.collect { app.log(app.getString(R.string.log_g_session_error, it.description)) } }
    val statesJob = app.scope.launch { session.state.collect { app.log(app.getString(R.string.log_g_session, it)) } }
    try {
      session.start()
      // Lo stato iniziale può essere già STOPPED: conta solo uno STOPPED arrivato dopo STARTING
      var seenStarting = false
      val started =
          withTimeoutOrNull(10_000) {
            session.state.first {
              if (it == DeviceSessionState.STARTING) seenStarting = true
              it == DeviceSessionState.STARTED || (seenStarting && it == DeviceSessionState.STOPPED)
            }
          }
      if (started != DeviceSessionState.STARTED) {
        app.log(app.getString(R.string.log_g_notstarted, started ?: "timeout"))
        return null
      }
      val added = session.addSpeech()
      val speech =
          added.getOrNull()
              ?: run {
                app.log(app.getString(R.string.log_g_nospeech, added.errorOrNull()?.description))
                return null
              }
      try {
        val startResult = speech.start()
        if (startResult.isFailure) {
          app.log(app.getString(R.string.log_g_speech_notstarted, startResult.errorOrNull()?.description))
          return null
        }
        val result =
            withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
              speech.transcriptions.filterNotNull().first { it.isFinal && it.text.isNotBlank() }
            }
        if (result == null) app.log(app.getString(R.string.log_g_timeout))
        return result?.text
      } finally {
        speech.stop()
        session.removeSpeech()
      }
    } finally {
      session.stop()
      errorsJob.cancel()
      statesJob.cancel()
    }
  }

  // --- ripiego: riconoscimento vocale del telefono (mic BT se disponibile) --------------------

  private suspend fun listenOnPhone(): String? {
    if (!SpeechRecognizer.isRecognitionAvailable(app)) {
      app.log(app.getString(R.string.log_p_unavailable))
      return null
    }
    val bt = useBluetoothMic()
    app.log(
        app.getString(
            R.string.log_p_listen,
            app.getString(if (bt) R.string.mic_glasses else R.string.mic_phone)))
    try {
      if (bt) delay(500) // tempo per aprire il canale SCO
      return withContext(Dispatchers.Main) {
        withTimeoutOrNull(LISTEN_TIMEOUT_MS + 5_000) { recognizeOnce() }
      }
    } finally {
      if (bt) audio.clearCommunicationDevice()
    }
  }

  private suspend fun recognizeOnce(): String? = suspendCancellableCoroutine { cont ->
    val recognizer = SpeechRecognizer.createSpeechRecognizer(app)
    cont.invokeOnCancellation { recognizer.destroy() }
    recognizer.setRecognitionListener(
        object : RecognitionListener {
          override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            recognizer.destroy()
            if (cont.isActive) cont.resume(text)
          }

          override fun onError(error: Int) {
            app.log(app.getString(R.string.log_p_error, error))
            recognizer.destroy()
            if (cont.isActive) cont.resume(null)
          }

          override fun onReadyForSpeech(params: Bundle?) {}
          override fun onBeginningOfSpeech() {}
          override fun onRmsChanged(rmsdB: Float) {}
          override fun onBufferReceived(buffer: ByteArray?) {}
          override fun onEndOfSpeech() {}
          override fun onPartialResults(partialResults: Bundle?) {}
          override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, app.getString(R.string.lang_tag))
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    recognizer.startListening(intent)
  }

  /** Porta l'ingresso audio sul Bluetooth (HFP) se c'è un dispositivo collegato. */
  private fun useBluetoothMic(): Boolean {
    val dev =
        audio.availableCommunicationDevices.firstOrNull {
          it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } ?: return false
    return audio.setCommunicationDevice(dev)
  }

  // --- Home Assistant -------------------------------------------------------------------------

  /** Prova l'URL di casa e, se non risponde, quello remoto. Ritorna il testo da leggere. */
  suspend fun askHomeAssistant(text: String): String {
    val urls = listOf(haUrl, haUrlRemote).filter { it.isNotBlank() }.distinct()
    var last: Exception? = null
    for ((i, url) in urls.withIndex()) {
      val reply =
          try {
            postConversation(url, text, agentId, connectTimeout = if (i == 0 && urls.size > 1) 2_500 else 8_000)
          } catch (e: Exception) {
            app.log("HA $url: ${describe(url, e)}")
            last = e
            continue
          }
      app.log("HA via $url")
      // HA non ha capito (nessun intent o nessuna entità): si passa all'agente LLM, se configurato.
      if (reply.notUnderstood && llmAgentId.isNotBlank() && llmAgentId != agentId) {
        app.log(app.getString(R.string.log_ha_fallback, reply.code, llmAgentId))
        status.value = app.getString(R.string.st_thinking)
        return try {
          val now = System.currentTimeMillis()
          val cid = llmConversationId?.takeIf { now - llmConversationAt < LLM_CONTEXT_MS }
          val llm = postConversation(url, text, llmAgentId, 8_000, LLM_TIMEOUT_MS, conversationId = cid)
          llmConversationId = llm.conversationId
          llmConversationAt = System.currentTimeMillis()
          llm.speech
        } catch (e: Exception) {
          app.log("LLM $llmAgentId: ${describe(url, e)}")
          app.getString(R.string.say_llm_timeout)
        }
      }
      return reply.speech
    }
    throw last ?: IllegalStateException(app.getString(R.string.err_no_url))
  }

  /** Conversazione LLM in corso: si riusa per qualche minuto, così "spegnila" sa di cosa si parla. */
  private var llmConversationId: String? = null
  private var llmConversationAt = 0L

  private class Reply(val speech: String, val code: String?, val conversationId: String?) {
    val notUnderstood get() = code == "no_intent_match" || code == "no_valid_targets"
  }

  /** Messaggio d'errore con l'IP risolto, per capire da che strada passa il telefono. */
  private suspend fun describe(url: String, e: Exception): String =
      withContext(Dispatchers.IO) {
        val host = runCatching { URL(url).host }.getOrNull()
        val ips = runCatching { InetAddress.getAllByName(host).joinToString { it.hostAddress ?: "?" } }
            .getOrElse { app.getString(R.string.err_dns) }
        "${e.javaClass.simpleName}: ${e.message} [$host → $ips]"
      }

  private suspend fun postConversation(
      baseUrl: String,
      text: String,
      agent: String,
      connectTimeout: Int,
      readTimeout: Int = 15_000,
      conversationId: String? = null,
  ): Reply =
      withContext(Dispatchers.IO) {
        val conn = URL("$baseUrl/api/conversation/process").openConnection() as HttpURLConnection
        try {
          conn.requestMethod = "POST"
          conn.connectTimeout = connectTimeout
          conn.readTimeout = readTimeout
          conn.doOutput = true
          conn.setRequestProperty("Authorization", "Bearer $haToken")
          conn.setRequestProperty("Content-Type", "application/json")
          val body =
              JSONObject()
                  .put("text", text)
                  .put("language", app.getString(R.string.lang))
                  .apply { if (agent.isNotBlank()) put("agent_id", agent) }
                  .apply { if (conversationId != null) put("conversation_id", conversationId) }
          conn.outputStream.use { it.write(body.toString().toByteArray()) }
          val code = conn.responseCode
          if (code !in 200..299) error("HTTP $code")
          val json = JSONObject(conn.inputStream.bufferedReader().readText())
          val response = json.optJSONObject("response")
          val speech =
              response
                  ?.optJSONObject("speech")
                  ?.optJSONObject("plain")
                  ?.optString("speech")
                  ?.takeIf { it.isNotBlank() } ?: app.getString(R.string.say_done)
          val errorCode =
              if (response?.optString("response_type") == "error")
                  response.optJSONObject("data")?.optString("code")
              else null
          Reply(speech, errorCode, json.optString("conversation_id").takeIf { it.isNotBlank() })
        } finally {
          conn.disconnect()
        }
      }

  // --- uscita audio ---------------------------------------------------------------------------

  private fun beep() {
    try {
      ToneGenerator(AudioManager.STREAM_MUSIC, 80).apply {
        startTone(ToneGenerator.TONE_PROP_BEEP, 150)
      }
    } catch (_: RuntimeException) {}
  }

  suspend fun speak(text: String) {
    val engine = tts
    if (!ttsReady || engine == null) {
      app.log(app.getString(R.string.log_tts_notready, text))
      return
    }
    suspendCancellableCoroutine { cont ->
      engine.setOnUtteranceProgressListener(
          object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
              if (cont.isActive) cont.resume(Unit)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
              if (cont.isActive) cont.resume(Unit)
            }
          })
      engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "casa-${System.nanoTime()}")
    }
  }

  companion object {
    // I valori predefiniti dipendono dall'edizione (app/build.gradle.kts)
    const val DEFAULT_URL = BuildConfig.HA_URL_DEF
    const val DEFAULT_URL_REMOTE = BuildConfig.HA_URL_REMOTE_DEF
    const val DEFAULT_AGENT = "conversation.home_assistant"
    const val DEFAULT_LLM_AGENT = BuildConfig.LLM_AGENT_DEF
    const val LLM_TIMEOUT_MS = 45_000
    const val LLM_CONTEXT_MS = 3 * 60_000L
    const val LISTEN_TIMEOUT_MS = 10_000L
  }
}
