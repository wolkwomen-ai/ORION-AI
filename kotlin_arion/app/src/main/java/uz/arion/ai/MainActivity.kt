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
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private lateinit var plusButton: TextView
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
        val menu = actionText("☰", Color.WHITE, Color.rgb(35,35,35), 24) { openDrawer() }
        header.addView(menu, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(OrbView(this), LinearLayout.LayoutParams(dp(40), dp(40)))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        titleBox.addView(TextView(this).apply {
            text = "ARION"; textSize = 19f; setTextColor(Color.rgb(30,30,30)); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        titleBox.addView(TextView(this).apply {
            text = "AI yordamchi"; textSize = 11f; setTextColor(Color.GRAY); gravity = Gravity.CENTER
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        val memoryButton = actionText("◉", Color.WHITE, Color.rgb(16,163,127), 23) { openMemory() }
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
        plusButton = actionText("+", Color.rgb(235,235,235), Color.rgb(35,35,35), 27) {
            Toast.makeText(this, "Qo‘shimcha imkoniyatlar keyingi bosqichda ulanadi", Toast.LENGTH_SHORT).show()
        }
        composer.addView(plusButton, LinearLayout.LayoutParams(dp(40), dp(40)))

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

    // ChatGPT uslubidagi chap yon menyu; mavjud ARION amallari saqlanadi.
    private fun openDrawer() {
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { root.removeView(this) }
        }
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(34), dp(14), dp(18))
            setBackgroundColor(Color.WHITE)
            elevation = dp(18).toFloat()
            setOnClickListener { }
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "ARION AI"
                textSize = 21f
                setTextColor(Color.rgb(25, 35, 55))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = "Shaxsiy sun’iy intellekt yordamchi"
                textSize = 11f
                setTextColor(Color.GRAY)
            })
        }
        heading.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(actionText("×", Color.rgb(245,246,248), Color.DKGRAY, 25) {
            root.removeView(scrim)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        drawer.addView(heading)

        val newChat = TextView(this).apply {
            text = "＋   Yangi suhbat"
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(25, 35, 55))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(12), 0)
            background = rounded(Color.rgb(240,246,255), 16)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                root.removeView(scrim)
                this@MainActivity.newChat()
            }
        }
        drawer.addView(newChat, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(26)
            bottomMargin = dp(20)
        })

        val scrollMenu = ScrollView(this).apply { isFillViewport = true }
        val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun section(label: String) {
            items.addView(TextView(this).apply {
                text = label
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(130, 138, 150))
                setPadding(dp(8), dp(14), dp(8), dp(8))
            })
        }
        fun item(icon: String, label: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(8), 0)
                background = rounded(Color.WHITE, 12)
                isClickable = true
                isFocusable = true
            }
            row.addView(TextView(this).apply {
                text = icon
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(55, 65, 80))
            }, LinearLayout.LayoutParams(dp(34), dp(46)))
            row.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.rgb(45, 50, 60))
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, -1, 1f))
            row.setOnClickListener {
                root.removeView(scrim)
                action()
            }
            items.addView(row, LinearLayout.LayoutParams(-1, dp(48)))
        }

        section("ISH VA SUHBAT")
        item("▧", "Rasm tanlash") { chooseImage() }
        item("◉", "Xotira") { openMemory() }
        item("✦", "Agent rejimi") { toggleAgentMode() }
        section("QO‘SHIMCHA IMKONIYATLAR")
        item("☁", "Ob-havo") { openWeather() }
        item("▤", "TXT fayl yaratish") { createTxtFile() }
        item("⌕", "Internet qidiruvi") { openInternetSearch() }
        item("⚙", "Telefon sozlamalari") { openPhoneSettings() }
        item("🎙", "Ovozli suhbat") { openVoiceMode() }
        scrollMenu.addView(items)
        drawer.addView(scrollMenu, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = TextView(this).apply {
            text = "ARION AI  •  1.6"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(dp(8), dp(14), dp(8), dp(4))
        }
        drawer.addView(footer)
        scrim.addView(drawer, FrameLayout.LayoutParams(dp(310), -1, Gravity.START))
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
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
        // ChatGPT uslubidagi ixcham jonli rejim: faqat A belgisi va composer ichidagi X.
        plusButton.visibility = View.GONE
        micButton.visibility = View.GONE
        sendButton.text = "×"
        sendButton.contentDescription = "Ovozli suhbatni yopish"
        sendButton.setOnClickListener { closeVoiceMode() }

        val voiceMark = OrbView(this).apply {
            minimalMode = true
            alpha = 0f
            scaleX = 1.25f
            scaleY = 1.25f
        }
        orb = voiceMark
        val markLp = FrameLayout.LayoutParams(dp(124), dp(124), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(74)
        }
        voicePanel = voiceMark
        voiceMark.translationY = -dp(76).toFloat()
        root.addView(voiceMark, markLp)
        voiceMark.animate()
            .alpha(1f)
            .scaleX(0.88f)
            .scaleY(0.88f)
            .translationY(0f)
            .setDuration(480)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
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
        plusButton.visibility = View.VISIBLE
        micButton.visibility = View.VISIBLE
        sendButton.text = if (input.text.toString().isNotBlank()) "↑" else "≋"
        sendButton.contentDescription = if (input.text.toString().isNotBlank()) "Yuborish" else "Ovozli suhbat"
        sendButton.setOnClickListener {
            if (input.text.toString().trim().isNotEmpty()) sendMessage() else openVoiceMode()
        }
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

    // ARION premium animated A-mark: metallic 3D monogram, energy core and orbital rings.
    private inner class OrbView(context: Context) : View(context) {
        var minimalMode = false
        private var rotation = 0f
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = Color.rgb(76, 190, 255)
            alpha = 205
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(210, 247, 255)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val unit = minOf(width, height).toFloat()
            if (unit <= 0f) return
            val scale = unit / 100f
            val pulse = 1f + 0.025f * kotlin.math.sin(Math.toRadians(rotation.toDouble())).toFloat()
            val s = scale * pulse

            if (!minimalMode) {
                // Normal app icon keeps its metallic aura and orbital rings.
                glowPaint.shader = android.graphics.RadialGradient(
                    cx, cy, unit * 0.49f,
                    intArrayOf(0x6644BFFF, 0x223A8FFF, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.62f, 1f), android.graphics.Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, unit * 0.49f, glowPaint)

                val orbitRect = android.graphics.RectF(
                    cx - unit * 0.47f, cy - unit * 0.19f,
                    cx + unit * 0.47f, cy + unit * 0.19f
                )
                val orbitSave = canvas.save()
                canvas.rotate(rotation, cx, cy)
                ringPaint.strokeWidth = maxOf(1f, unit * 0.018f)
                ringPaint.alpha = 125
                canvas.drawOval(orbitRect, ringPaint)
                canvas.rotate(58f, cx, cy)
                ringPaint.alpha = 205
                canvas.drawOval(orbitRect, ringPaint)
                canvas.restoreToCount(orbitSave)
            }

            val markSave = canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(s, s)

            val aPath = Path().apply {
                moveTo(-29f, 32f)
                lineTo(-5f, -30f)
                quadTo(0f, -40f, 5f, -30f)
                lineTo(29f, 32f)
            }
            val barPath = Path().apply {
                moveTo(-17f, 12f)
                quadTo(0f, 7f, 17f, 12f)
            }

            // Dark offset extrusion gives the A a machined, dimensional edge.
            canvas.save()
            canvas.translate(2.4f, 3.2f)
            edgePaint.color = Color.rgb(2, 13, 36)
            edgePaint.strokeWidth = 12.5f
            edgePaint.alpha = 245
            canvas.drawPath(aPath, edgePaint)
            canvas.drawPath(barPath, edgePaint)
            canvas.restore()

            // Main polished titanium face with a cool blue reflection.
            facePaint.shader = android.graphics.LinearGradient(
                -28f, -34f, 28f, 35f,
                intArrayOf(
                    Color.rgb(245, 253, 255),
                    Color.rgb(117, 211, 255),
                    Color.rgb(35, 105, 190),
                    Color.rgb(220, 245, 255)
                ),
                floatArrayOf(0f, 0.34f, 0.72f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            facePaint.strokeWidth = 8.5f
            facePaint.alpha = 255
            canvas.drawPath(aPath, facePaint)
            canvas.drawPath(barPath, facePaint)
            facePaint.shader = null

            // Fine white specular edge.
            facePaint.color = Color.WHITE
            facePaint.alpha = 145
            facePaint.strokeWidth = 1.35f
            canvas.drawPath(aPath, facePaint)
            facePaint.alpha = 95
            canvas.drawPath(barPath, facePaint)

            if (!minimalMode) {
                // Energy core and orbiting particles are reserved for the full app logo.
                val coreRadius = 7.2f + lastRms.coerceIn(0f, 12f) * 0.18f
                glowPaint.shader = android.graphics.RadialGradient(
                    0f, 10f, coreRadius * 2.5f,
                    intArrayOf(Color.WHITE, 0xFF72D8FF.toInt(), 0x003B9DFF.toInt()),
                    floatArrayOf(0f, 0.36f, 1f), android.graphics.Shader.TileMode.CLAMP
                )
                canvas.drawCircle(0f, 10f, coreRadius * 2.5f, glowPaint)
                glowPaint.shader = null
                nodePaint.color = Color.WHITE
                canvas.drawCircle(0f, 10f, coreRadius * 0.34f, nodePaint)

                val radians = Math.toRadians((rotation * 1.7f).toDouble())
                val px = kotlin.math.cos(radians).toFloat() * 39f
                val py = kotlin.math.sin(radians).toFloat() * 15f
                nodePaint.color = Color.rgb(150, 231, 255)
                canvas.drawCircle(px, py, 2.5f, nodePaint)
                canvas.drawCircle(-px, -py, 1.7f, nodePaint)
            }

            canvas.restoreToCount(markSave)

            rotation = (rotation + 0.72f + lastRms.coerceIn(0f, 12f) * 0.035f) % 360f
            postInvalidateDelayed(32)
        }
    }
}
