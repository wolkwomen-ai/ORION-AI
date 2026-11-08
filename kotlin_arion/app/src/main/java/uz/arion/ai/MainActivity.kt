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
import android.media.MediaPlayer
import io.github.whitemagic2014.tts.TTS as EdgeTTS
import io.github.whitemagic2014.tts.TTSVoice
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray
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
    private var edgePlayer: MediaPlayer? = null
    private var listening = false
    private var liveMode = false
    private var voicePanel: View? = null
    private var voiceDock: View? = null
    private var orb: OrbView? = null
    private var lastRms = 0f
    private var waitingForAnswer = false
    private var pendingVoiceMode = false
    private var voicePaused = false
    private var agentMode = false
    private var pendingTxtContent = ""
    private var pendingTxtName = "arion_eslatma.txt"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: kontentni status/nav paneli va klaviaturadan aniq inset bilan ajratamiz.
        WindowCompat.setDecorFitsSystemWindows(window, false)

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
        if (getSharedPreferences("arion_config", Context.MODE_PRIVATE).getString("groq_api_key", "").isNullOrBlank()) {
            root.postDelayed({ showApiKeyDialog() }, 500)
        }
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
            popup.menu.add("Rasm tanlash").setOnMenuItemClickListener { chooseImage(); true }
            popup.menu.add("Xotira").setOnMenuItemClickListener { openMemory(); true }
            popup.menu.add("Agent rejimi").setOnMenuItemClickListener { toggleAgentMode(); true }
            popup.menu.add("Ob-havo").setOnMenuItemClickListener { openWeather(); true }
            popup.menu.add("TXT fayl yaratish").setOnMenuItemClickListener { createTxtFile(); true }
            popup.menu.add("Internet qidiruvi").setOnMenuItemClickListener { openInternetSearch(); true }
            popup.menu.add("Telefon sozlamalari").setOnMenuItemClickListener { openPhoneSettings(); true }
            popup.menu.add("Ovozli suhbat").setOnMenuItemClickListener { openVoiceMode(); true }
            popup.menu.add("API kalitni ulash").setOnMenuItemClickListener { showApiKeyDialog(); true }
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
        val memoryButton = actionText("＋", Color.WHITE, Color.rgb(35,35,35), 27) { newChat() }
        header.addView(memoryButton, LinearLayout.LayoutParams(dp(48), dp(48)))
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
        val plus = actionText("+", Color.rgb(235,235,235), Color.rgb(35,35,35), 27) { anchor ->
            val menu = PopupMenu(this@MainActivity, anchor)
            menu.menu.add("Rasm tanlash").setOnMenuItemClickListener { chooseImage(); true }
            menu.menu.add("Xotira").setOnMenuItemClickListener { openMemory(); true }
            menu.menu.add("Yangi suhbat").setOnMenuItemClickListener { newChat(); true }
            menu.show()
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

        sendButton = actionText("◖", Color.rgb(37,99,235), Color.WHITE, 23) {
            if (input.text.toString().trim().isNotEmpty()) sendMessage() else openVoiceMode()
        }
        composer.addView(sendButton, LinearLayout.LayoutParams(dp(42), dp(42)))

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank()
                sendButton.text = if (hasText) "↑" else "◖"
                sendButton.contentDescription = if (hasText) "Yuborish" else "Ovozli suhbat"
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val composerLp = LinearLayout.LayoutParams(-1, -2)
        composerLp.topMargin = dp(8)
        page.addView(composer, composerLp)

        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // Pastki yozish paneli telefon navigatsiya tugmalari ustiga tushmasin.
        // Klaviatura ochilganda composer IME yuqorisiga ko‘tariladi.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
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

    private fun toggleAgentMode() {
        agentMode = !agentMode
        val message = if (agentMode) {
            "Agent rejimi yoqildi. Murakkab topshiriqlar uchun rejim tanlandi."
        } else {
            "Agent rejimi o'chirildi."
        }
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun openMemory() {
        val prefs = getSharedPreferences("arion_memory", Context.MODE_PRIVATE)
        val editor = EditText(this).apply {
            hint = "ARION eslab qolishi kerak bo'lgan ma'lumotlar"
            setText(prefs.getString("notes", "").orEmpty())
            minLines = 4
            maxLines = 8
            textSize = 16f
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(TextView(this@MainActivity).apply {
                text = "Bu eslatmalar telefon xotirasida saqlanadi."
                textSize = 13f
                setTextColor(Color.GRAY)
            })
            addView(editor)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("ARION XOTIRASI")
            .setView(box)
            .setPositiveButton("SAQLASH") { _, _ ->
                prefs.edit().putString("notes", editor.text.toString()).apply()
                Toast.makeText(this, "Xotira saqlandi", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("TOZALASH") { _, _ ->
                prefs.edit().remove("notes").apply()
                Toast.makeText(this, "Xotira tozalandi", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("YOPISH", null)
            .show()
    }

    private fun openWeather() {
        val uri = android.net.Uri.parse("https://www.google.com/search?q=bugungi+ob-havo")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun openInternetSearch() {
        val queryInput = EditText(this).apply {
            hint = "Nimani qidiramiz?"
            textSize = 16f
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("INTERNET QIDIRUVI")
            .setView(queryInput)
            .setPositiveButton("QIDIRISH") { _, _ ->
                val query = queryInput.text.toString().trim()
                if (query.isNotEmpty()) {
                    val uri = android.net.Uri.parse(
                        "https://www.google.com/search?q=" + android.net.Uri.encode(query)
                    )
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            }
            .setNegativeButton("BEKOR", null)
            .show()
    }

    private fun openPhoneSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Telefon sozlamalari ochilmadi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createTxtFile() {
        val nameInput = EditText(this).apply {
            hint = "Fayl nomi"
            setText("arion_eslatma")
            textSize = 16f
        }
        val bodyInput = EditText(this).apply {
            hint = "Fayl matni"
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP
            textSize = 16f
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(nameInput)
            addView(bodyInput)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("TXT FAYL YARATISH")
            .setView(box)
            .setPositiveButton("SAQLASH") { _, _ ->
                var name = nameInput.text.toString().trim().ifEmpty { "arion_eslatma" }
                name = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
                if (!name.endsWith(".txt")) name += ".txt"
                pendingTxtName = name
                pendingTxtContent = bodyInput.text.toString()
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, pendingTxtName)
                }
                try {
                    startActivityForResult(intent, 705)
                } catch (e: Exception) {
                    Toast.makeText(this, "TXT saqlash oynasi ochilmadi", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("BEKOR", null)
            .show()
    }

    private fun chooseImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Rasm tanlash"), 704)
        } catch (_: Exception) {
            Toast.makeText(this, "Rasm tanlash ochilmadi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleMenuActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            704 -> {
                Toast.makeText(
                    this,
                    "Rasm tanlandi. Hozirgi server versiyasida rasm tahlili ulanmagan.",
                    Toast.LENGTH_LONG
                ).show()
                addMessage("ARION", "Rasm tanlandi. Rasmni tahlil qilish uchun tasvirni serverga yuborish funksiyasi kerak.")
            }
            705 -> {
                try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(pendingTxtContent.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Faylni yozish oqimi ochilmadi")
                    Toast.makeText(this, "TXT fayl saqlandi: $pendingTxtName", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "TXT saqlash xatosi: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Deprecated("Deprecated API; used for document and image picker compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        handleMenuActivityResult(requestCode, resultCode, data)
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
        askBackend(question, true)
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun showApiKeyDialog() {
        val editor = EditText(this).apply {
            hint = "Groq API kalitini shu yerga joylang"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            textSize = 15f
        }
        val current = getSharedPreferences("arion_config", Context.MODE_PRIVATE)
            .getString("groq_api_key", "").orEmpty()
        if (current.isNotBlank()) editor.setText(current)
        val note = TextView(this).apply {
            text = "Pydroid 3 endi kerak emas. ARION Groq serveriga to‘g‘ridan-to‘g‘ri ulanadi. Pydroid ichidagi orion_key.txt faylida turgan kalitni nusxalab shu yerga joylang. Kalit faqat shu telefonda saqlanadi."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(10))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(note)
            addView(editor)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("ARION — API KALIT")
            .setView(box)
            .setPositiveButton("SAQLASH") { _, _ ->
                val key = editor.text.toString().trim()
                if (key.isBlank()) {
                    Toast.makeText(this, "API kalit kiritilmadi", Toast.LENGTH_LONG).show()
                } else {
                    getSharedPreferences("arion_config", Context.MODE_PRIVATE)
                        .edit().putString("groq_api_key", key).apply()
                    Toast.makeText(this, "API kalit telefonda saqlandi", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("BEKOR", null)
            .show()
    }

    private fun askBackend(question: String, speakAnswer: Boolean) {
        waitingForAnswer = true
        status.text = "ARION javob tayyorlamoqda…"
        thread {
            var answer: String
            try {
                val prefs = getSharedPreferences("arion_config", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("groq_api_key", "").orEmpty().trim()
                if (apiKey.isBlank()) throw IllegalStateException("API kalit ulanmagan. ☰ menyudan «API kalitni ulash» bo‘limini oching.")
                val historyPrefs = getSharedPreferences("arion_chat", Context.MODE_PRIVATE)
                val saved = try { JSONArray(historyPrefs.getString("messages", "[]")) } catch (_: Exception) { JSONArray() }
                val messages = org.json.JSONArray()
                messages.put(JSONObject()
                    .put("role", "system")
                    .put("content", "Sen ARION nomli ilg‘or shaxsiy sun’iy intellekt yordamchisisan. Foydalanuvchiga o‘zbek tilida ravon, aniq va foydali javob ber. O‘zbek imlosida o‘ va g‘ harflarini to‘g‘ri yoz. Oddiy savollarga ixcham javob ber, batafsil so‘ralsa keng tushuntir. Bilmagan narsangni to‘qima. Kod so‘ralsa to‘liq va ishlatishga tayyor kod ber. API kalitlarni oshkor qilma."))
                val from = maxOf(0, saved.length() - 16)
                for (i in from until saved.length()) messages.put(saved.getJSONObject(i))
                messages.put(JSONObject().put("role", "user").put("content", question))
                val payload = JSONObject()
                    .put("model", "qwen/qwen3.8-27b")
                    .put("messages", messages)
                    .put("temperature", 0.6)
                    .put("max_completion_tokens", 1400)
                val conn = (URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection)
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 95000
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val json = JSONObject(response)
                if (code !in 200..299) {
                    val detail = json.optJSONObject("error")?.optString("message")
                        ?: "HTTP $code"
                    throw IllegalStateException("Groq xatosi ($code): $detail")
                }
                answer = json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
                    ?.takeIf { it.isNotBlank() } ?: "ARION bo‘sh javob oldi."
                val updated = JSONArray()
                val keepFrom = maxOf(0, saved.length() - 28)
                for (i in keepFrom until saved.length()) updated.put(saved.getJSONObject(i))
                updated.put(JSONObject().put("role", "user").put("content", question))
                updated.put(JSONObject().put("role", "assistant").put("content", answer))
                historyPrefs.edit().putString("messages", updated.toString()).apply()
                conn.disconnect()
            } catch (e: Exception) {
                answer = e.message ?: "Internet yoki AI server bilan aloqa xatosi."
            }
            runOnUiThread {
                waitingForAnswer = false
                status.text = ""
                addMessage("ARION", answer)
                if (answer.contains("API kalit ulanmagan")) showApiKeyDialog()
                if (speakAnswer) {
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
        voiceDock?.let { root.removeView(it) }
        voiceDock = null
        // Ovoz oynasi butun ekran bo‘ylab: tashqi karta/ramka chizilmaydi.
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            clipChildren = false
            clipToPadding = false
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(20))
            background = null
            clipChildren = false
            clipToPadding = false
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "ARION"; textSize = 18f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(35,35,35))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(-1, dp(44)))
        column.addView(header)
        val center = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            background = null
            clipChildren = false
            clipToPadding = false
            clipToOutline = false
        }
        orb = OrbView(this).apply {
            background = null
            clipToOutline = false
            elevation = 0f
        }
        center.addView(orb, FrameLayout.LayoutParams(dp(360), dp(360), Gravity.CENTER))
        var dragStartY = 0f
        center.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { dragStartY = event.rawY; true }
                MotionEvent.ACTION_UP -> {
                    val delta = event.rawY - dragStartY
                    if (delta > dp(65)) dockVoicePanel()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        column.addView(center, LinearLayout.LayoutParams(-1, 0, 1f))
        val hint = TextView(this).apply {
            text = "Gapiring — ARION tinglayapti"
            textSize = 15f
            setTextColor(Color.rgb(105, 105, 105))
            gravity = Gravity.CENTER
        }
        val footer = FrameLayout(this)
        footer.addView(hint, FrameLayout.LayoutParams(-1, dp(58), Gravity.CENTER))
        column.addView(footer, LinearLayout.LayoutParams(-1, dp(64)))
        scrim.addView(column, FrameLayout.LayoutParams(-1, -1))

        // X tugmasi dumaloq shar yoki ichki panelga emas, ekranning past-o‘ng
        // burchagiga biriktiriladi.
        scrim.addView(actionText("×", Color.rgb(245,245,245), Color.DKGRAY, 28) {
            closeVoiceMode()
        }, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.RIGHT or Gravity.BOTTOM).apply {
            rightMargin = dp(18)
            bottomMargin = dp(18)
        })
        voicePanel = scrim
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
    }

    private fun dockVoicePanel() {
        if (!liveMode) return
        voicePanel?.let { root.removeView(it) }
        voicePanel = null

        // Pastga surilganda faqat shar qoladi: u yozish maydoni ustida,
        // ekranning aynan o‘rtasida turadi. Yonida mikrofon yoki X yo‘q.
        val dock = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = dp(4).toFloat()
        }
        val miniOrb = OrbView(this)
        dock.addView(miniOrb, FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER))
        dock.setOnClickListener {
            root.removeView(dock)
            voiceDock = null
            showVoicePanel()
        }
        voiceDock = dock
        root.addView(dock, FrameLayout.LayoutParams(-1, dp(104), Gravity.BOTTOM).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            // Composer ustida joylashadi, yozish maydonini to‘sib qo‘ymaydi.
            bottomMargin = dp(66)
        })
    }

    private fun resumeLiveListening() {
        if (!liveMode || voicePaused || waitingForAnswer || listening || isFinishing) return
        if (recognizer == null && !ensureRecognizer()) return
        status.text = "Sizni tinglayapman…"
        voicePaused = false
        try { recognizer?.startListening(speechIntent()) }
        catch (_: Exception) { status.text = "Mikrofonni qayta yoqish uchun 🎙 bosing" }
    }

    // O‘zbekcha o‘ va g‘ harflarini Edge TTS uchun yagona imloga keltiramiz.
    private fun normalizeUzbekSpeech(text: String): String {
        return text.replace(Regex("(?i)(o|g)[‘’ʻʼ\\u02BB\\u02BC\\u0060']")) { match ->
            match.groupValues[1] + "ʻ"
        }
    }

    // Edge TTS: Madina ovozi, biroz tezroq talaffuz va ishonchli MP3 ijrosi.
    private fun speak(text: String) {
        val speechText = normalizeUzbekSpeech(text).take(3500)
        status.text = "Madina ovozi tayyorlanmoqda…"
        thread {
            try {
                val voice = TTSVoice.provides()
                    .firstOrNull { it.shortName == "uz-UZ-MadinaNeural" }
                    ?: throw IllegalStateException("uz-UZ-MadinaNeural ovozi topilmadi")

                val audio = EdgeTTS(voice, speechText)
                    .voiceRate("+5%")
                    .formatMp3()
                    .transToAudioStream()
                    ?: throw IllegalStateException("Edge TTS audio qaytarmadi")

                val audioBytes = audio.toByteArray()
                if (audioBytes.size < 1000) {
                    throw IllegalStateException("Ovoz fayli bo‘sh yoki to‘liq kelmadi")
                }
                val audioFile = java.io.File(cacheDir, "arion_madina.mp3")
                audioFile.writeBytes(audioBytes)

                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    try {
                        edgePlayer?.setOnCompletionListener(null)
                        edgePlayer?.release()
                        edgePlayer = MediaPlayer().apply {
                            setAudioAttributes(
                                android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            setDataSource(audioFile.absolutePath)
                            setOnPreparedListener { player ->
                                status.text = "Madina gapiryapti…"
                                player.start()
                            }
                            setOnCompletionListener { player ->
                                player.release()
                                if (edgePlayer === player) edgePlayer = null
                                status.text = ""
                                if (liveMode && !waitingForAnswer) resumeLiveListening()
                            }
                            setOnErrorListener { player, _, _ ->
                                player.release()
                                if (edgePlayer === player) edgePlayer = null
                                status.text = "Ovozni ijro etib bo‘lmadi"
                                speakWithPhoneVoice(speechText)
                                true
                            }
                            prepareAsync()
                        }
                    } catch (e: Exception) {
                        status.text = "MP3 ijro xatosi: ${e.localizedMessage}"
                        speakWithPhoneVoice(speechText)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Madina ovozi ulanmayapti — zaxira ovoz ishga tushmoqda"
                    speakWithPhoneVoice(speechText)
                }
            }
        }
    }

    // Edge TTS ishlamasa ham javob ovozsiz qolmasligi uchun telefon TTS zaxirasi.
    private fun speakWithPhoneVoice(text: String) {
        try {
            if (ttsReady) tts?.setSpeechRate(1.05f)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "arion-fallback")
            root.postDelayed({
                if (status.text.toString().contains("zaxira ovoz")) status.text = ""
                if (liveMode && !waitingForAnswer) resumeLiveListening()
            }, (text.length.coerceAtMost(3500) * 65L).coerceIn(1800L, 18000L))
        } catch (e: Exception) {
            status.text = "Ovoz chiqarish xatosi: ${e.localizedMessage}"
            if (liveMode) resumeLiveListening()
        }
    }

    private fun closeVoiceMode() {
        liveMode = false
        voicePaused = false
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        tts?.stop()
        voicePanel?.let { root.removeView(it) }
        voicePanel = null
        voiceDock?.let { root.removeView(it) }
        voiceDock = null
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
        try { edgePlayer?.stop() } catch (_: Exception) {}
        edgePlayer?.release()
        edgePlayer = null
        super.onDestroy()
    }

    private inner class OrbView(context: Context) : View(context) {
        private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val t = System.currentTimeMillis() / 1000.0
            val cx = width / 2f
            val cy = height / 2f
            val voice = (lastRms.coerceIn(0f, 12f) / 12f)
            val breathe = kotlin.math.sin(t * 1.35).toFloat()
            val radius = minOf(width, height) * (0.445f + voice * 0.025f + breathe * 0.008f)
            val driftX = kotlin.math.sin(t * 0.55).toFloat() * radius * 0.045f
            val driftY = kotlin.math.cos(t * 0.72).toFloat() * radius * 0.035f

            // Yumshoq ko‘k nur: chiziq yoki tashqi ramka yo‘q.
            glowPaint.shader = android.graphics.RadialGradient(
                cx, cy, radius * 1.75f,
                intArrayOf(0x665A9BFF, 0x225A9BFF, 0x005A9BFF),
                floatArrayOf(0f, 0.62f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius * 1.75f, glowPaint)

            // ChatGPT ovozli rejimidagidek silliq, bir butun ko‘k nurli shar.
            orbPaint.shader = android.graphics.RadialGradient(
                cx - radius * 0.24f + driftX,
                cy - radius * 0.30f + driftY,
                radius * 1.62f,
                intArrayOf(
                    0xFFF4FAFF.toInt(),
                    0xFFD6ECFF.toInt(),
                    0xFFADD9FF.toInt(),
                    0xFF82C2FF.toInt(),
                    0xFF5AA8F5.toInt()
                ),
                floatArrayOf(0f, 0.20f, 0.46f, 0.76f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, orbPaint)
            orbPaint.shader = null
            glowPaint.shader = null

            // Ovoz kelganda shar juda yengil kattalashadi.
            postInvalidateDelayed(32)
        }
    }
}