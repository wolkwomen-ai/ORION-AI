package uz.arion.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var root: FrameLayout
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var micButton: TextView
    private lateinit var sendButton: TextView
    private lateinit var voiceButton: TextView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var liveMode = false
    private var voicePanel: View? = null
    private var orb: OrbView? = null
    private var lastRms = 0f
    private var waitingForAnswer = false
    private var pendingVoiceMode = false
    private var voicePaused = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 15 / API 35 edge-to-edge holatini o‘chirib,
        // tizim paneli insetlarini Android o‘zi hisoblashiga ruxsat beramiz.
        // Shunda composer navigatsiya paneli ustiga tushmaydi va adjustResize ishlaydi.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            Window.setDecorFitsSystemWindows(window, true)
        }

        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        tts = TextToSpeech(this, this)
        buildScreen()
        addMessage("ARION", "Assalomu alaykum! Men ARIONman. Sizga qanday yordam beray?")
    }

    private fun buildScreen() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val menu = actionText("☰", Color.WHITE, Color.rgb(35,35,35), 24) {
            val popup = PopupMenu(this@MainActivity, it)
            popup.menu.add("Yangi suhbat").setOnMenuItemClickListener { newChat(); true }
            popup.menu.add("Ovozli suhbat").setOnMenuItemClickListener { openVoiceMode(); true }
            popup.show()
        }
        header.addView(menu, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        titleBox.addView(TextView(this).apply {
            text = "ARION"; textSize = 19f; setTextColor(Color.rgb(30,30,30)); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        titleBox.addView(TextView(this).apply {
            text = "AI yordamchi"; textSize = 11f; setTextColor(Color.GRAY); gravity = Gravity.CENTER
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        val newChat = actionText("✎", Color.WHITE, Color.rgb(35,35,35), 22) { newChat() }
        header.addView(newChat, LinearLayout.LayoutParams(dp(48), dp(48)))
        page.addView(header)

        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(16))
        }
        scroll.addView(chat)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        status = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        }
        page.addView(status, LinearLayout.LayoutParams(-1, dp(18)))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            background = rounded(Color.rgb(247,247,248), 28)
            background = rounded(Color.rgb(247,247,248), 28).apply {
                setStroke(dp(1), Color.rgb(225,225,225))
            }
            elevation = dp(1).toFloat()
        }
        val plus = actionText("+", Color.rgb(235,235,235), Color.rgb(35,35,35), 27) {
            Toast.makeText(this, "Qo‘shimcha imkoniyatlar keyingi bosqichda ulanadi", Toast.LENGTH_SHORT).show()
        }
        composer.addView(plus, LinearLayout.LayoutParams(dp(40), dp(40)))

        input = EditText(this).apply {
            hint = "Xabar yozing"
            textSize = 16f
            minLines = 1
            maxLines = 5
            setTextColor(Color.rgb(30,30,30))
            setHintTextColor(Color.GRAY)
            setPadding(dp(10), dp(8), dp(6), dp(8))
            background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))

        micButton = actionText("🎙", Color.TRANSPARENT, Color.rgb(20,20,20), 19) { startDictation() }
        composer.addView(micButton, LinearLayout.LayoutParams(dp(40), dp(40)))

        sendButton = actionText("≋", Color.rgb(37,99,235), Color.WHITE, 23) {
            if (input.text.toString().trim().isNotEmpty()) sendMessage() else openVoiceMode()
        }
        composer.addView(sendButton, LinearLayout.LayoutParams(dp(42), dp(42)))

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank()
                sendButton.text = if (hasText) "↑" else "≋"
                sendButton.contentDescription = if (hasText) "Yuborish" else "Ovozli suhbat"
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val composerLp = LinearLayout.LayoutParams(-1, -2)
        composerLp.topMargin = dp(8)
        page.addView(composer, composerLp)

        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun actionText(label: String, fill: Int, foreground: Int, size: Int, action: (View) -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = size.toFloat()
            gravity = Gravity.CENTER
            setTextColor(foreground)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
            isClickable = true
            isFocusable = true
            setOnClickListener { action(this) }
            contentDescription = label
        }
    }

    private fun addMessage(who: String, message: String) {
        val isUser = who == "Siz"
        val bubble = TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(Color.rgb(35,35,35))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextIsSelectable(true)
            if (isUser) {
                background = rounded(Color.rgb(240,240,240), 20)
            } else {
                background = null
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            addView(bubble, LinearLayout.LayoutParams(
                if (isUser) (resources.displayMetrics.widthPixels * 0.78f).toInt() else -1, -2
            ))
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(dp(2), dp(4), dp(2), dp(4))
        chat.addView(row, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun newChat() {
        chat.removeAllViews()
        addMessage("ARION", "Yangi suhbat boshlandi. Sizga qanday yordam beray?")
    }

    private fun sendMessage() {
        val question = input.text.toString().trim()
        if (question.isEmpty() || waitingForAnswer) return
        input.setText("")
        hideKeyboard()
        addMessage("Siz", question)
        askBackend(question, false)
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun askBackend(question: String, speakAnswer: Boolean) {
        waitingForAnswer = true
        status.text = "ARION javob tayyorlamoqda…"
        thread {
            var answer: String
            try {
                val conn = (URL("http://127.0.0.1:8765/ask").openConnection() as HttpURLConnection)
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 95000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val body = JSONObject().put("question", question).toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(response)
                if (code in 200..299) answer = json.optString("answer", "ARION bo‘sh javob oldi.")
                else answer = json.optString("error", "Server xatosi: $code")
                conn.disconnect()
            } catch (e: Exception) {
                answer = "ARION serveriga ulanib bo‘lmadi. Pydroid 3 ichida ARION serverini ishga tushiring (127.0.0.1:8765)."
            }
            runOnUiThread {
                waitingForAnswer = false
                status.text = ""
                addMessage("ARION", answer)
                if (speakAnswer && liveMode) {
                    speak(answer)
                } else if (liveMode) {
                    resumeLiveListening()
                }
            }
        }
    }

    private fun ensureRecognizer(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Telefonda ovozni aniqlash xizmati topilmadi", Toast.LENGTH_LONG).show()
            return false
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 702)
            return false
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listening = true; status.text = if (liveMode) "Sizni tinglayapman…" else "Ovoz yozilmoqda…" }
                override fun onBeginningOfSpeech() { status.text = if (liveMode) "Gapiring…" else "Yozilmoqda…" }
                override fun onRmsChanged(rmsdB: Float) { lastRms = rmsdB; orb?.invalidate() }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false; status.text = "Ovoz qayta ishlanmoqda…" }
                override fun onError(error: Int) {
                    listening = false
                    if (!liveMode) {
                        micButton.text = "🎙"
                        input.hint = "Xabar yozing"
                    }
                    if (liveMode && !waitingForAnswer) {
                        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            status.text = "Ovozni aniqlash xatosi"
                        }
                        if (liveMode) root.postDelayed({ resumeLiveListening() }, 700)
                    } else {
                        status.text = ""
                    }
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    if (!liveMode) {
                        micButton.text = "🎙"
                        input.hint = "Xabar yozing"
                    }
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (phrase.isNotBlank()) {
                        if (liveMode) {
                            addMessage("Siz", phrase)
                            askBackend(phrase, true)
                        } else {
                            input.setText((input.text.toString() + " " + phrase).trim())
                            input.setSelection(input.text.length)
                            status.text = "Matn tayyor — ↑ tugmasini bosing"
                        }
                    } else if (liveMode) {
                        resumeLiveListening()
                    } else status.text = ""
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        return true
    }

    private fun speechIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "uz-UZ")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun startDictation() {
        if (listening) {
            recognizer?.stopListening()
            return
        }
        if (!ensureRecognizer()) return
        liveMode = false
        micButton.text = "■"
        input.hint = "Ovoz yozilmoqda… gapiring"
        status.text = "Ovoz yozilmoqda…"
        try { recognizer?.startListening(speechIntent()) }
        catch (_: Exception) {
            micButton.text = "🎙"
            input.hint = "Xabar yozing"
            status.text = "Mikrofonni ishga tushirib bo‘lmadi"
        }
    }

    private fun openVoiceMode() {
        if (liveMode) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceMode = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 702)
            return
        }
        if (!ensureRecognizer()) return
        liveMode = true
        voicePaused = false
        hideKeyboard()
        showVoicePanel()
        resumeLiveListening()
    }

    private fun showVoicePanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(28))
            background = rounded(Color.rgb(249,249,249), 30).apply {
                setStroke(dp(1), Color.rgb(230,230,230))
            }
            elevation = dp(12).toFloat()
        }
        val top = TextView(this).apply {
            text = "ARION bilan ovozli suhbat"
            textSize = 19f
            setTextColor(Color.rgb(35,35,35))
            gravity = Gravity.CENTER
        }
        panel.addView(top, LinearLayout.LayoutParams(-1, dp(44)))
        orb = OrbView(this)
        panel.addView(orb, LinearLayout.LayoutParams(dp(190), dp(190)))
        val hint = TextView(this).apply {
            text = "Gapiring — ARION tinglayapti"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }
        panel.addView(hint, LinearLayout.LayoutParams(-1, dp(42)))
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        val mute = actionText("Ⅱ", Color.rgb(230,230,230), Color.DKGRAY, 22) {
            voicePaused = true
            if (listening) recognizer?.cancel()
            listening = false
            status.text = "Mikrofon pauzada"
            hint.text = "Davom ettirish uchun mikrofonni bosing"
        }
        val end = actionText("×", Color.rgb(210,55,55), Color.WHITE, 28) { closeVoiceMode() }
        val resume = actionText("🎙", Color.rgb(16,163,127), Color.WHITE, 20) { resumeLiveListening() }
        buttons.addView(mute, LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(18) })
        buttons.addView(end, LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(18) })
        buttons.addView(resume, LinearLayout.LayoutParams(dp(54), dp(54)))
        panel.addView(buttons)
        panel.setOnTouchListener(object : View.OnTouchListener {
            var downY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { downY = event.rawY; return true }
                    MotionEvent.ACTION_UP -> {
                        val delta = event.rawY - downY
                        if (abs(delta) > dp(70)) closeVoiceMode()
                        return true
                    }
                }
                return true
            }
        })
        val scrim = FrameLayout(this).apply { setBackgroundColor(0x66000000) }
        scrim.addView(panel, FrameLayout.LayoutParams(-1, dp(430), Gravity.BOTTOM).apply {
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(12)
        })
        scrim.setOnClickListener { closeVoiceMode() }
        panel.setOnClickListener { }
        voicePanel = scrim
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
    }

    private fun resumeLiveListening() {
        if (!liveMode || voicePaused || waitingForAnswer || listening || isFinishing) return
        if (recognizer == null && !ensureRecognizer()) return
        status.text = "Sizni tinglayapman…"
        voicePaused = false
        try { recognizer?.startListening(speechIntent()) }
        catch (_: Exception) { status.text = "Mikrofonni qayta yoqish uchun 🎙 bosing" }
    }

    private fun speak(text: String) {
        if (!liveMode || !ttsReady) { if (liveMode) resumeLiveListening(); return }
        tts?.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "arion-answer")
        root.postDelayed({
            if (liveMode && !waitingForAnswer) resumeLiveListening()
        }, (text.length.coerceAtMost(3500) * 65L).coerceIn(1800L, 18000L))
    }

    private fun closeVoiceMode() {
        liveMode = false
        voicePaused = false
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        tts?.stop()
        voicePanel?.let { root.removeView(it) }
        voicePanel = null
        orb = null
        status.text = ""
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 702 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (pendingVoiceMode) {
                pendingVoiceMode = false
                if (ensureRecognizer()) {
                    liveMode = true
                    voicePaused = false
                    hideKeyboard()
                    showVoicePanel()
                    resumeLiveListening()
                }
            } else if (liveMode) resumeLiveListening() else startDictation()
        } else if (requestCode == 702) {
            Toast.makeText(this, "Ovoz uchun mikrofon ruxsati kerak", Toast.LENGTH_LONG).show()
        }
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            ttsReady = true
            val code = tts?.setLanguage(Locale("uz", "UZ"))
            if (code == TextToSpeech.LANG_MISSING_DATA || code == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale("ru", "RU"))
            }
        }
    }

    override fun onDestroy() {
        liveMode = false
        try { recognizer?.destroy() } catch (_: Exception) {}
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private inner class OrbView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(16,163,127); style = Paint.Style.FILL }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3310A37F; style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(4).toFloat(); strokeCap = Paint.Cap.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = dp(48).toFloat() + lastRms.coerceIn(0f, 12f) * dp(1).toFloat()
            canvas.drawCircle(cx, cy, radius + dp(18).toFloat(), glow)
            canvas.drawCircle(cx, cy, radius, fill)
            val path = Path()
            for (i in 0..4) {
                val x = cx + (i - 2) * dp(13)
                val h = dp(10).toFloat() + (lastRms.coerceIn(0f, 12f) * dp(2).toFloat()) * (if (i % 2 == 0) 1f else .65f)
                if (i == 0) path.moveTo(x, cy - h) else path.lineTo(x, cy - h)
                path.lineTo(x, cy + h)
            }
            canvas.drawPath(path, line)
            postInvalidateDelayed(120)
        }
    }
}
