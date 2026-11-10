package ir.sadegh.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var status: TextView
    private lateinit var recognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private var pendingNumber: String? = null

    private val perms = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        tts = TextToSpeech(this, this)

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "تشخیص گفتار روی این گوشی در دسترس نیست"
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(listener)

        findViewById<Button>(R.id.listenBtn).setOnClickListener {
            if (hasAllPermissions()) startListening()
            else ActivityCompat.requestPermissions(this, perms, 1)
        }
    }

    private fun hasAllPermissions() = perms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (hasAllPermissions()) startListening()
        else status.text = "برای کار کردن، همهٔ مجوزها لازم است"
    }

    override fun onInit(s: Int) {
        if (s == TextToSpeech.SUCCESS) tts.language = Locale("fa", "IR")
    }

    private fun say(text: String) {
        status.text = text
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sadegh")
    }

    private fun startListening() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        status.text = "گوش می‌دم..."
        recognizer.startListening(i)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return say("متوجه نشدم")
            handle(text)
        }
        override fun onPartialResults(partial: Bundle) {
            partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.let { status.text = it }
        }
        override fun onError(error: Int) = say("خطا در تشخیص صدا (کد $error)، دوباره بگو")
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handle(text: String) {
        when (val cmd = CommandParser.parse(text)) {
            is Command.Call -> {
                val match = ContactFinder.find(this, cmd.name)
                if (match == null) {
                    say("مخاطبی به اسم ${cmd.name} پیدا نکردم")
                } else {
                    say("دارم به ${match.name} زنگ می‌زنم")
                    call(match.number)
                }
            }
            Command.Unknown -> say("این فرمان را بلد نیستم: $text")
        }
    }

    private fun call(number: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))
        startActivity(intent)
    }

    override fun onDestroy() {
        if (::recognizer.isInitialized) recognizer.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}
