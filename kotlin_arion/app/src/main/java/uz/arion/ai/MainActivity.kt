package uz.arion.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.inputmethod.EditorInfo
import android.text.InputType
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val voiceRequest = 701

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(32, 91, 160)
        window.navigationBarColor = Color.rgb(32, 91, 160)
        tts = TextToSpeech(this, this)
        buildScreen()
        addMessage("ARION", "Assalomu alaykum! Men ARIONman. Kotlin asosidagi Android ilovasi ishga tushdi.")
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(245, 247, 252))
        }
        val header = TextView(this).apply {
            text = "✦ ARION AI"
            textSize = 25f
            setTextColor(Color.rgb(25, 45, 75))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(header)
        status = TextView(this).apply {
            text = "Tayyor • Android Kotlin"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        root.addView(status)
        scroll = ScrollView(this)
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Xabar yozing..."
            textSize = 16f
            minLines = 1
            maxLines = 5
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            showSoftInputOnFocus = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.rgb(215, 222, 232))
            }
            setOnClickListener {
                requestFocus()
                post {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        val inputParams = LinearLayout.LayoutParams(0, -2, 1f)
        inputParams.setMargins(0, 0, dp(6), 0)
        composer.addView(input, inputParams)
        composer.addView(makeButton("🎙", ::startVoice))
        composer.addView(makeButton("➤", ::sendMessage))
        root.addView(composer)

        // Keep the chat composer above both the keyboard and Android navigation bar.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
                left = bars.left
                top = bars.top
                right = bars.right
                bottom = maxOf(bars.bottom, ime.bottom)
            } else {
                left = insets.systemWindowInsetLeft
                top = insets.systemWindowInsetTop
                right = insets.systemWindowInsetRight
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(dp(12) + left, dp(8) + top, dp(12) + right, dp(8) + bottom)
            insets
        }

        setContentView(root)
        root.requestApplyInsets()
        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )
    }

    private fun makeButton(label: String, action: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { action() } }

    private fun addMessage(who: String, message: String) {
        val bubble = TextView(this).apply {
            text = "$who\n$message"
            textSize = 16f
            setTextColor(Color.rgb(25, 35, 55))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(if (who == "Siz") Color.rgb(220, 235, 255) else Color.WHITE)
            setTextIsSelectable(true)
        }
        val params = LinearLayout.LayoutParams(-1, -2)
        params.setMargins(0, dp(5), 0, dp(5))
        chat.addView(bubble, params)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isEmpty()) return
        input.setText("")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        addMessage("Siz", message)
        addMessage("ARION", "Xabaringiz qabul qilindi. Keyingi bosqichda AI serveriga ulaymiz.")
    }

    private fun startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 702)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "ARIONga gapiring")
        }
        try { startActivityForResult(intent, voiceRequest) }
        catch (_: Exception) { addMessage("ARION", "Ovozli tanish xizmati telefonda mavjud emas.") }
    }

    @Deprecated("Legacy activity result API kept for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == voiceRequest && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) { input.setText(spoken); sendMessage() }
        }
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            ttsReady = true
            val languageResult = tts?.setLanguage(Locale("uz", "UZ"))
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale("ru", "RU"))
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
