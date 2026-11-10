package ir.sadegh.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import kotlin.concurrent.thread

/**
 * سرویس پس‌زمینه: با موتور آفلاین Vosk منتظر کلمهٔ «صادق» می‌ماند.
 * بعد از شنیدن، یک بوق می‌زند و فرمان را با تشخیص گفتار گوگل می‌گیرد.
 */
class WakeService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "sadegh_wake"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "ir.sadegh.assistant.STOP"
        const val SAMPLE_RATE = 16000
        private const val TAG = "SadeghWake"

        @Volatile
        var running = false
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var destroyed = false
    @Volatile private var stopWake = false
    private var model: Model? = null
    private var wakeThread: Thread? = null
    private var speech: SpeechRecognizer? = null
    private var commandActive = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val commandTimeout = Runnable { finishCommand(1500) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL_ID, "گوش دادن صادق", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("fa", "IR")
            ttsReady = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            goForeground("در حال آماده‌سازی...")
        } catch (e: Exception) {
            Log.e(TAG, "foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            acquireWakeLock()
            thread(name = "wake-init") { prepareModel() }
        }
        return START_NOT_STICKY
    }

    // ---------- اعلان ----------

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("دستیار صادق")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "خاموش", stop)
            .setOngoing(true)
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateStatus(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "notify", e)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sadegh:wake").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    // ---------- آماده‌سازی مدل ----------

    private fun prepareModel() {
        try {
            val dir = File(filesDir, "vosk-model")
            val marker = File(filesDir, "vosk-model.ready")
            if (!marker.exists()) {
                updateStatus("در حال آماده‌سازی مدل (فقط بار اول)...")
                dir.deleteRecursively()
                copyAssets("model", dir)
                marker.writeText("ok")
            }
            model = Model(dir.absolutePath)
            main.post { startWakeLoop() }
        } catch (e: Throwable) {
            Log.e(TAG, "model", e)
            updateStatus("خطا در بارگذاری مدل: ${e.message}")
            main.post { stopSelf() }
        }
    }

    private fun copyAssets(src: String, dst: File) {
        val names = assets.list(src) ?: emptyArray()
        if (names.isEmpty()) {
            try {
                dst.parentFile?.mkdirs()
                assets.open(src).use { input ->
                    dst.outputStream().use { out -> input.copyTo(out) }
                }
            } catch (e: FileNotFoundException) {
                dst.mkdirs()   // پوشهٔ خالی
            }
        } else {
            dst.mkdirs()
            for (name in names) copyAssets("$src/$name", File(dst, name))
        }
    }

    // ---------- گوش دادن به کلمهٔ «صادق» ----------

    private fun hasWakeWord(json: String): Boolean {
        return try {
            val o = JSONObject(json)
            val t = (o.optString("text", "") + " " + o.optString("partial", ""))
                .replace('ي', 'ی')
                .replace('ك', 'ک')
            t.split(" ").any { it == "صادق" }
        } catch (e: Exception) {
            false
        }
    }

    private fun startWakeLoop() {
        if (destroyed || wakeThread?.isAlive == true) return
        val m = model ?: return
        stopWake = false
        wakeThread = thread(name = "wake-loop") {
            var detected = false
            var failed = false
            var ar: AudioRecord? = null
            var rec: Recognizer? = null
            try {
                rec = Recognizer(m, SAMPLE_RATE.toFloat(), "[\"صادق\", \"[unk]\"]")
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val bufBytes = maxOf(minBuf, SAMPLE_RATE)
                ar = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes
                )
                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("microphone is busy")
                }
                ar.startRecording()
                updateStatus("بگو «صادق»...")
                val buf = ShortArray(SAMPLE_RATE / 5)
                while (!stopWake) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n < 0) {
                        failed = true
                        break
                    }
                    if (n == 0) continue
                    val json = if (rec.acceptWaveForm(buf, n)) rec.result else rec.partialResult
                    if (hasWakeWord(json)) {
                        detected = true
                        break
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "wake loop", e)
                failed = true
            } finally {
                try { ar?.stop() } catch (_: Throwable) {}
                try { ar?.release() } catch (_: Throwable) {}
                try { rec?.close() } catch (_: Throwable) {}
            }
            if (detected) {
                main.post { onWakeDetected() }
            } else if (failed && !stopWake && !destroyed) {
                updateStatus("میکروفون در دسترس نیست؛ دوباره تلاش می‌کنم...")
                main.postDelayed({ startWakeLoop() }, 3000)
            }
        }
    }

    // ---------- بعد از شنیدن «صادق» ----------

    private fun onWakeDetected() {
        if (destroyed) return
        commandActive = true
        updateStatus("بله؟ بگو...")
        beep()
        main.postDelayed({ listenForCommand() }, 450)
        main.postDelayed(commandTimeout, 20000)
    }

    private fun beep() {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {
            Log.w(TAG, "beep", e)
        }
    }

    private fun listenForCommand() {
        if (destroyed || !commandActive) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("تشخیص گفتار در دسترس نیست")
            finishCommand(1500)
            return
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        speech = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text == null) {
                    say("متوجه نشدم")
                    finishCommand(1500)
                } else {
                    handle(text)
                }
            }
            override fun onError(error: Int) {
                updateStatus("خطا در تشخیص (کد $error)")
                finishCommand(1500)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
        }
        sr.startListening(i)
    }

    private fun handle(text: String) {
        when (val cmd = CommandParser.parse(text)) {
            is Command.Call -> {
                val match = ContactFinder.find(this, cmd.name)
                if (match == null) {
                    say("مخاطبی به اسم ${cmd.name} پیدا نکردم")
                    finishCommand(1500)
                } else {
                    say("دارم به ${match.name} زنگ می‌زنم")
                    main.postDelayed({ placeCall(match.number) }, 1200)
                    finishCommand(9000)
                }
            }
            Command.Unknown -> {
                say("این فرمان را بلد نیستم: $text")
                finishCommand(1500)
            }
        }
    }

    private fun placeCall(number: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            say("مجوز تماس داده نشده")
            return
        }
        try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (e: Exception) {
            Log.w(TAG, "placeCall", e)
            try {
                val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (e2: Exception) {
                Log.e(TAG, "call failed", e2)
                say("تماس برقرار نشد")
            }
        }
    }

    private fun say(text: String) {
        updateStatus(text)
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sadegh")
    }

    private fun cleanupSpeech() {
        try { speech?.destroy() } catch (_: Exception) {}
        speech = null
    }

    private fun finishCommand(resumeDelay: Long) {
        if (!commandActive) return
        commandActive = false
        main.removeCallbacks(commandTimeout)
        cleanupSpeech()
        if (!destroyed) main.postDelayed({ startWakeLoop() }, resumeDelay)
    }

    override fun onDestroy() {
        destroyed = true
        stopWake = true
        main.removeCallbacksAndMessages(null)
        cleanupSpeech()
        try { wakeThread?.join(1500) } catch (_: Exception) {}
        try { model?.close() } catch (_: Throwable) {}
        model = null
        try { tts?.shutdown() } catch (_: Exception) {}
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        running = false
        super.onDestroy()
    }
}
