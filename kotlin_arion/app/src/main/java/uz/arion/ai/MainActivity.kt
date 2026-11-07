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
        window.statusBarColor = Color.rgb(255, 255, 255)
        window.navigationBarColor = Color.rgb(255, 255, 255)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        tts = TextToSpeech(this, this)
        buildScreen()
        addMessage("ARION", "Assalomu alaykum! Men ARIONman. Sizga qanday yordam beray?")
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        val header = TextView(this).apply {
            text = "ARION"
            textSize = 21f
            setTextColor(Color.rgb(25, 25, 25))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(header)
        status = TextView(this).apply {
            text = "AI yordamchi"
            textSize = 12f
            setTextColor(Color.rgb(120, 120, 120))
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        root.addView(status)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(12))
        }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // ChatGPT uslubidagi bitta yumaloq xabar paneli.
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(6), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.rgb(247, 247, 248))
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.rgb(225, 225, 225))
            }
            elevation = dp(1).toFloat()
        }

        input = EditText(this).apply {
            hint = "Xabar yuboring"
            textSize = 16f
            minLines = 1
            maxLines = 5
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            showSoftInputOnFocus = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setTextColor(Color.rgb(30, 30, 30))
            setHintTextColor(Color.rgb(135, 135, 135))
            setPadding(dp(10), dp(9), dp(6), dp(9))
            background = null
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
            }
            setOnClickListener {
                requestFocus()
                post {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(makeRoundAction("🎙", Color.rgb(235, 235, 235), Color.rgb(45, 45, 45), ::startVoice))
        val sendParams = LinearLayout.LayoutParams(dp(42), dp(42))
        sendParams.setMargins(dp(6), 0, 0, 0)
        composer.addView(makeRoundAction("↑", Color.rgb(10, 132, 255), Color.WHITE, ::sendMessage), sendParams)
        val composerParams = LinearLayout.LayoutParams(-1, -2)
        composerParams.setMargins(0, dp(8), 0, dp(4))
        root.addView(composer, composerParams)

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

    private fun makeRoundAction(label: String, fill: Int, foreground: Int, action: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = if (label == "↑") 25f else 19f
            gravity = Gravity.CENTER
            setTextColor(foreground)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
            }
            if (layoutParams == null) {
                val size = dp(40)
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
            setOnClickListener { action() }
        }

    private fun addMessage(who: String, message: String) {
        val bubble = TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(Color.rgb(35, 35, 35))
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GradientDrawable().apply {
                setColor(if (who == "Siz") Color.rgb(240, 240, 240) else Color.WHITE)
                cornerRadius = dp(18).toFloat()
            }
            setTextIsSelectable(true)
        }
        val params = LinearLayout.LayoutParams(-1, -2)
        params.setMargins(0, dp(4), 0, dp(4))
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
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
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
