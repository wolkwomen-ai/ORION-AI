package uz.arion.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.media.MediaRecorder
import java.io.File
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
import android.text.Editable
import android.text.TextWatcher
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false
    private lateinit var sendButton: TextView
    private lateinit var micButton: View

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
        val plusButton = makeRoundAction("+", Color.rgb(235, 235, 235), Color.rgb(35, 35, 35)) {
            Toast.makeText(this, "Qo‘shimcha imkoniyatlar keyingi bosqichda qo‘shiladi", Toast.LENGTH_SHORT).show()
        }
        plusButton.textSize = 28f
        composer.addView(plusButton, LinearLayout.LayoutParams(dp(40), dp(40)))

        val inputParams = LinearLayout.LayoutParams(0, -2, 1f)
        inputParams.setMargins(dp(4), 0, dp(4), 0)
        composer.addView(input, inputParams)

        micButton = makeMicAction(::startRecording)
        composer.addView(micButton)
        val sendParams = LinearLayout.LayoutParams(dp(40), dp(40))
        sendButton = makeRoundAction("↑", Color.rgb(20, 20, 20), Color.WHITE, ::sendOrStop)
        sendButton.visibility = View.GONE
        composer.addView(sendButton, sendParams)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isRecording) {
                    val hasText = !s.isNullOrBlank()
                    micButton.visibility = if (hasText) View.GONE else View.VISIBLE
                    sendButton.visibility = if (hasText) View.VISIBLE else View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
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

    private fun makeMicAction(action: () -> Unit): View {
        return object : View(this) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(35, 35, 35)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            init {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(235, 235, 235))
                }
                setOnClickListener { action() }
                contentDescription = "Mikrofon"
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f
                val cy = height / 2f
                canvas.drawRoundRect(
                    cx - dp(4), cy - dp(10), cx + dp(4), cy + dp(4),
                    dp(4).toFloat(), dp(4).toFloat(), paint
                )
                val path = android.graphics.Path().apply {
                    moveTo(cx - dp(9), cy)
                    cubicTo(cx - dp(9), cy + dp(13), cx + dp(9), cy + dp(13), cx + dp(9), cy)
                    moveTo(cx, cy + dp(10))
                    lineTo(cx, cy + dp(15))
                    moveTo(cx - dp(5), cy + dp(15))
                    lineTo(cx + dp(5), cy + dp(15))
                }
                canvas.drawPath(path, paint)
            }
        }
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

    private fun sendOrStop() {
        if (isRecording) {
            stopRecording()
            addMessage("Siz", "🎙 Ovozli xabar yozildi: " + (recordingFile?.name ?: "audio"))
            addMessage("ARION", "Ovoz yozuvi saqlandi. Ovozli xabarni AI orqali tinglab javob berish uchun server ulanishi hali kerak.")
            sendButton.text = "↑"
            sendButton.visibility = View.GONE
            micButton.visibility = if (input.text.isNullOrBlank()) View.VISIBLE else View.GONE
            status.text = "AI yordamchi"
            return
        }
        sendMessage()
    }

    private fun startRecording() {
        if (isRecording) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 702)
            return
        }
        try {
            val file = File(cacheDir, "arion_voice_" + System.currentTimeMillis() + ".m4a")
            val newRecorder = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioEncodingBitRate(128000)
            newRecorder.setAudioSamplingRate(44100)
            newRecorder.setOutputFile(file.absolutePath)
            newRecorder.prepare()
            newRecorder.start()
            recorder = newRecorder
            recordingFile = file
            isRecording = true
            micButton.visibility = View.GONE
            sendButton.visibility = View.VISIBLE
            sendButton.text = "〰"
            status.text = "● Ovoz yozilmoqda… To‘xtatish uchun tugmani bosing"
        } catch (e: Exception) {
            isRecording = false
            recorder?.release()
            recorder = null
            addMessage("ARION", "Mikrofonni ishga tushirib bo‘lmadi: " + (e.localizedMessage ?: "xatolik"))
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        try {
            recorder?.stop()
        } catch (_: RuntimeException) {
            recordingFile?.delete()
        } finally {
            recorder?.release()
            recorder = null
            isRecording = false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 702 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else if (requestCode == 702) {
            addMessage("ARION", "Ovoz yozish uchun mikrofon ruxsatini yoqing.")
        }
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isEmpty()) return
        input.setText("")
        micButton.visibility = View.VISIBLE
        sendButton.visibility = View.GONE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        addMessage("Siz", message)
        addMessage("ARION", "Xabaringiz qabul qilindi. Keyingi bosqichda AI serveriga ulaymiz.")
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
