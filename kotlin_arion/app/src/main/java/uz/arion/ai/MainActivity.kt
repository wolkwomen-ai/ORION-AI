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
import android.media.MediaPlayer
import java.io.File
import java.io.FileOutputStream
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
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var micButton: TextView
    private lateinit var sendButton: TextView
    private lateinit var voiceButton: TextView
    private lateinit var plusButton: TextView
    private var recognizer: SpeechRecognizer? = null
    private var edgePlayer: MediaPlayer? = null
    private var listening = false
    private var liveMode = false
    private var voicePanel: View? = null
    private var collapsedVoiceView: View? = null
    private var orb: OrbView? = null
    private var lastRms = 0f
    private var waitingForAnswer = false
    private var pendingVoiceMode = false
    private var voicePaused = false
    private var agentMode = false
    private val agentCore = ArionAgentCore()
    private var agentLedgerLoaded = false
    private var pendingTxtContent = ""
    private var pendingTxtName = "arion_eslatma.txt"
    private var liveTranscriptView: TextView? = null
    private val chatHistory = mutableListOf<JSONObject>()
    private val groqModel = "qwen/qwen3.8-27b"
    private val groqUrl = "https://api.groq.com/openai/v1/chat/completions"
    private val openRouterUrl = "https://openrouter.ai/api/v1/chat/completions"
    private val openRouterFreeModel = "openrouter/free"

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
        buildScreen()
        addMessage("ARION", "Assalomu alaykum! Men ARIONman. Sizga qanday yordam beray?")
    }

    private fun buildScreen() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
        }
        val menu = actionText("☰", Color.WHITE, Color.rgb(35,35,35), 24) { openDrawer() }
        header.addView(menu, LinearLayout.LayoutParams(dp(48), dp(48)))
        val headerOrb = OrbView(this)
        orb = headerOrb
        header.addView(headerOrb, LinearLayout.LayoutParams(dp(68), dp(68)))
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

        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(Color.WHITE)
        }
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
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
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && liveMode) {
                    voicePaused = true
                    listening = false
                    try { recognizer?.cancel() } catch (_: Exception) {}
                    micButton.text = "🎙"
                    micButton.contentDescription = "Ovozli tinglashni davom ettirish"
                    status.text = "Matn yozishingiz mumkin"
                }
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
                sendButton.text = if (hasText) "↑" else if (liveMode) "×" else "≋"
                sendButton.contentDescription = if (hasText) "Yuborish" else if (liveMode) "Ovozli suhbatni yopish" else "Ovozli suhbat"
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val composerLp = LinearLayout.LayoutParams(-1, -2)
        composerLp.topMargin = dp(8)
        page.addView(composer, composerLp)

        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // Klaviatura ochilganda butun pastki yozish paneli uning ustiga ko‘tariladi.
        // Chat maydoni oq fonda qoladi; xabarlar klaviatura ortida berkinib ketmaydi.
        var imeWasVisible = false
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, if (imeVisible) ime.bottom else bars.bottom)
            if (imeVisible && !imeWasVisible) {
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
            imeWasVisible = imeVisible
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
        item("🧠", "O‘zini rivojlantirish") { openSelfDevelopment() }
        item("✦", "Agent rejimi") { toggleAgentMode() }
        item("🧠", "Mustaqil agent markazi") { openAgentCenter() }
        section("QO‘SHIMCHA IMKONIYATLAR")
        item("☁", "Ob-havo") { openWeather() }
        item("▤", "TXT fayl yaratish") { createTxtFile() }
        item("⌕", "Internet qidiruvi") { openInternetSearch() }
        item("⚙", "Telefon sozlamalari") { openPhoneSettings() }
        item("🎙", "Ovozli suhbat") { openVoiceMode() }
        scrollMenu.addView(items)
        drawer.addView(scrollMenu, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = TextView(this).apply {
            text = "ARION AI  •  3.4"
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


    // ARION 3.4: vazifalar va moliyaviy daftar uchun boshqaruv markazi.
    // Hozirgi versiyada agent faqat tadqiqot/reja/qoralama yaratadi; tashqi amallar yoqilmagan.
    private fun openAgentCenter() {
        loadAgentLedgerOnce()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        panel.addView(TextView(this).apply {
            text = "ARION vazifalarni rejalashtiradi va daromad-xarajatlarni hisoblaydi. Xabar yuborish, buyurtmani tasdiqlash, pul o‘tkazish va real savdo hozircha avtomatik bajarilmaydi."
            textSize = 13f; setTextColor(Color.DKGRAY)
        })
        fun card(title: String, detail: String, click: () -> Unit) {
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rounded(Color.rgb(246,248,251), 14); isClickable = true; isFocusable = true
            }
            v.addView(TextView(this).apply { text = title; textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(35,45,60)) })
            v.addView(TextView(this).apply { text = detail; textSize = 12f; setTextColor(Color.GRAY) })
            v.setOnClickListener { click() }
            panel.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        card("＋  Yangi agent topshirig‘i", "Tadqiqot, xabar qoralamasi yoki buyurtma loyihasi") { showAgentTaskDialog() }
        card("📒  Daromad yoki xarajat kiritish", "Summalar telefonda saqlanadi") { showAgentLedgerDialog() }
        card("📊  Hisob-kitobni ko‘rish", "Jami daromad, xarajat va sof natija") { showAgentLedgerSummary() }
        card("📋  Joriy vazifalar", "Agent yadrosiga yuborilgan topshiriqlar") { showAgentTasks() }
        android.app.AlertDialog.Builder(this).setTitle("ARION — MUSTAQIL AGENT")
            .setView(ScrollView(this).apply { addView(panel) }).setNegativeButton("YOPISH", null).show()
    }

    private fun showAgentTaskDialog() {
        val title = EditText(this).apply { hint = "Topshiriq nomi"; textSize = 15f }
        val detail = EditText(this).apply { hint = "Nima qilish kerak?"; minLines = 3; gravity = Gravity.TOP; textSize = 14f }
        val type = Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Tadqiqot", "Xabar qoralamasi", "Buyurtma loyihasi"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(4))
            addView(title); addView(type); addView(detail)
        }
        android.app.AlertDialog.Builder(this).setTitle("YANGI AGENT TOPSHIRIG‘I").setView(box)
            .setPositiveButton("REJALASHTIRISH") { _, _ ->
                val name = title.text.toString().trim()
                val info = detail.text.toString().trim()
                if (name.isBlank() || info.isBlank()) {
                    Toast.makeText(this, "Topshiriq nomi va tafsilotini kiriting", Toast.LENGTH_LONG).show()
                } else {
                    val actionType = when (type.selectedItemPosition) {
                        1 -> AgentActionType.DRAFT_MESSAGE
                        2 -> AgentActionType.CREATE_ORDER_DRAFT
                        else -> AgentActionType.RESEARCH
                    }
                    val task = agentCore.submit(info, AgentAction(title = name, details = info, type = actionType))
                    val stateText = if (task.state == AgentTaskState.READY) "Rejalashtirishga qabul qilindi" else "Tasdiq kutilmoqda"
                    addMessage("ARION", "Agent topshirig‘i saqlandi: $name. Holati: $stateText. Hozir bu topshiriq reja sifatida qayd etildi; tashqi amal bajarilmadi.")
                    status.text = "Agent topshirig‘i qabul qilindi"
                    Toast.makeText(this, stateText, Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("BEKOR", null).show()
    }

    private fun showAgentTasks() {
        val tasks = agentCore.getTasks()
        val message = if (tasks.isEmpty()) "Hozircha vazifa yo‘q." else tasks.joinToString("\n\n") {
            "• ${it.action.title}\nHolati: ${it.state}\nTafsilot: ${it.action.details}"
        }
        android.app.AlertDialog.Builder(this).setTitle("AGENT VAZIFALARI")
            .setMessage(message).setPositiveButton("YOPISH", null).show()
    }

    private fun loadAgentLedgerOnce() {
        if (agentLedgerLoaded) return
        val rows = try { org.json.JSONArray(getSharedPreferences("arion_agent", Context.MODE_PRIVATE).getString("ledger", "[]")) }
            catch (_: Exception) { org.json.JSONArray() }
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            runCatching {
                agentCore.recordLedgerEntry(AgentLedgerEntry(
                    label = row.optString("label"), amountMinor = row.optLong("amount"),
                    currency = row.optString("currency", "UZS"), isIncome = row.optBoolean("income"),
                    timestampEpochMs = row.optLong("time")
                ))
            }
        }
        agentLedgerLoaded = true
    }

    private fun showAgentLedgerDialog() {
        loadAgentLedgerOnce()
        val label = EditText(this).apply { hint = "Izoh (masalan: ish haqi, oziq-ovqat)" }
        val amount = EditText(this).apply { hint = "Summa, so‘m"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val kind = Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Daromad", "Xarajat"))
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(4)); addView(label); addView(amount); addView(kind) }
        android.app.AlertDialog.Builder(this).setTitle("MOLIYAVIY DAFTAR").setView(box)
            .setPositiveButton("SAQLASH") { _, _ ->
                val value = runCatching { java.math.BigDecimal(amount.text.toString().trim()).movePointRight(2).longValueExact() }.getOrNull()
                if (label.text.isNullOrBlank() || value == null || value <= 0L) {
                    Toast.makeText(this, "Izoh va to‘g‘ri summani kiriting", Toast.LENGTH_LONG).show()
                } else {
                    val income = kind.selectedItemPosition == 0
                    val entry = AgentLedgerEntry(label = label.text.toString().trim(), amountMinor = value, currency = "UZS", isIncome = income)
                    agentCore.recordLedgerEntry(entry)
                    val prefs = getSharedPreferences("arion_agent", Context.MODE_PRIVATE)
                    val rows = try { org.json.JSONArray(prefs.getString("ledger", "[]")) } catch (_: Exception) { org.json.JSONArray() }
                    rows.put(JSONObject().put("label", entry.label).put("amount", entry.amountMinor)
                        .put("currency", entry.currency).put("income", entry.isIncome).put("time", entry.timestampEpochMs))
                    prefs.edit().putString("ledger", rows.toString()).apply()
                    Toast.makeText(this, "Hisobga saqlandi", Toast.LENGTH_SHORT).show()
                    showAgentLedgerSummary()
                }
            }.setNegativeButton("BEKOR", null).show()
    }

    private fun showAgentLedgerSummary() {
        loadAgentLedgerOnce()
        val entries = agentCore.getLedger()
        val income = entries.filter { it.isIncome && it.currency == "UZS" }.sumOf { it.amountMinor }
        val expense = entries.filter { !it.isIncome && it.currency == "UZS" }.sumOf { it.amountMinor }
        val fmt = java.text.NumberFormat.getIntegerInstance(java.util.Locale("uz", "UZ"))
        val recent = entries.takeLast(8).asReversed().joinToString("\n") {
            (if (it.isIncome) "+ " else "− ") + fmt.format(it.amountMinor / 100.0) + " so‘m — " + it.label
        }
        val body = "Daromad: ${fmt.format(income / 100.0)} so‘m\nXarajat: ${fmt.format(expense / 100.0)} so‘m\nSof natija: ${fmt.format((income-expense) / 100.0)} so‘m\n\nOxirgi yozuvlar:\n" + if (recent.isBlank()) "Hali yozuv yo‘q." else recent
        android.app.AlertDialog.Builder(this).setTitle("ARION HISOB-KITOBI")
            .setMessage(body).setPositiveButton("YOPISH", null).show()
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

    // ARION o'zini rivojlantirish markazi: foydalanuvchi nazoratidagi bilim, fikr va diagnostika.
    private fun openSelfDevelopment() {
        val prefs = getSharedPreferences("arion_development", Context.MODE_PRIVATE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        panel.addView(TextView(this).apply {
            text = "ARION o‘z faoliyatini tahlil qiladi, siz bergan fikr va bilimlarni saqlaydi hamda yaxshilanish rejasini taklif qiladi. Bu modelni avtomatik qayta o‘qitmaydi; saqlangan qaydlar keyingi suhbatlarda hisobga olinadi."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(12))
        })

        fun actionCard(title: String, detail: String, action: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rounded(Color.rgb(246, 248, 251), 14)
                isClickable = true
                isFocusable = true
            }
            card.addView(TextView(this).apply {
                text = title
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(35, 45, 60))
            })
            card.addView(TextView(this).apply {
                text = detail
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(0, dp(3), 0, 0)
            })
            card.setOnClickListener { action() }
            panel.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            })
        }

        lateinit var dialog: android.app.AlertDialog
        actionCard("🔎  O‘zini tahlil qilish", "Kuchli va yaxshilanishi kerak bo‘lgan tomonlarini tahlil qiladi.") {
            dialog.dismiss()
            sendDevelopmentTask("ARION, o‘z faoliyatingni halol tahlil qil. Hozirgi imkoniyatlaring, cheklovlaring, javob sifati, xatolar xavfi va foydalanuvchiga foydangni ajratib ko‘rsat. Haqiqiy ma’lumot bilan taxminni farqla. 5 ta aniq yaxshilanish taklifini ustuvorlik bo‘yicha ber. O‘zingda yo‘q imkoniyat bor deb da’vo qilma.")
        }
        actionCard("🗺  Rivojlanish rejasi", "Keyingi bosqichlar uchun aniq va bosqichma-bosqich reja tuzadi.") {
            dialog.dismiss()
            sendDevelopmentTask("ARION uchun amaliy rivojlanish rejasini tuz. Avval ishonchlilik va mavjud funksiyalarni saqlash, keyin xotira, tahlil, bilimlar bazasi, diagnostika va xavfsiz yangilanishni bosqichlarga ajrat. Har bosqichda maqsad, kerakli ish, sinov mezoni va xavfni yoz. Hozirgi Android ilova imkoniyatlaridan tashqaridagi ishlarni aniq belgilab qo‘y.")
        }
        actionCard("📚  Yangi bilim qo‘shish", "Siz kiritgan qoida yoki ma’lumotni keyingi suhbatlar uchun saqlaydi.") {
            dialog.dismiss()
            showDevelopmentNoteDialog("YANGI BILIM", "learning_notes", "ARION bilishi kerak bo‘lgan ma’lumot yoki qoida")
        }
        actionCard("📝  Xato yoki taklif bildirish", "ARION javobidagi xato va takliflaringizni saqlaydi.") {
            dialog.dismiss()
            showDevelopmentNoteDialog("FIKR VA XATOLAR", "feedback_notes", "Qaysi xato yoki kamchilikni tuzatish kerak?")
        }
        actionCard("💡  ARIONdan rivojlanish taklifi olish", "ARION o‘z imkoniyatlarini tahlil qilib, yaxshilanish takliflarini beradi.") {
            dialog.dismiss()
            sendDevelopmentTask("ARION, hozirgi ilova imkoniyatlaring va cheklovlaringni hisobga olib, xavfsiz va amaliy 5 ta rivojlanish taklifini tuz. Har bir taklif uchun foyda, kerakli kod o‘zgarishi, sinov usuli va xavfni yoz. O‘zing mustaqil bajara olmaydigan ishlarni ochiq ayt. Hech qanday o‘zgarish tasdiqlangan deb hisoblama.")
        }
        actionCard("🛠  Yangi o‘zgarish taklif qilish", "ARIONni yaxshilash uchun taklif yoki tuzatish talabini kiriting.") {
            dialog.dismiss()
            showChangeRequestDialog()
        }
        actionCard("📋  Takliflarni boshqarish", "Har bir o‘zgarishni tasdiqlash yoki rad etish faqat sizning qo‘lingizda.") {
            dialog.dismiss()
            showChangeRequests()
        }
        actionCard("🔐  Boshqaruv va ruxsatlar", "Avtomatik kod o‘zgartirish va avtomatik APK o‘rnatish o‘chirilgan.") {
            dialog.dismiss()
            showChangeControlInfo()
        }
        actionCard("🩺  Tizim diagnostikasi", "Ilovaning asosiy ruxsatlari va saqlangan rivojlanish qaydlarini tekshiradi.") {
            dialog.dismiss()
            showDevelopmentDiagnostics()
        }
        actionCard("📖  Rivojlanish qaydlari", "Saqlangan bilimlar va fikrlarni ko‘rish yoki tozalash.") {
            dialog.dismiss()
            showDevelopmentNotes()
        }

        dialog = android.app.AlertDialog.Builder(this)
            .setTitle("ARION — O‘ZINI RIVOJLANTIRISH")
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("YOPISH", null)
            .create()
        dialog.show()
    }

    private fun showChangeControlInfo() {
        val message = "ARION o‘zgarishlarni taklif qilishi va qayd etishi mumkin.\n\n" +
            "• Takliflar sizning roziligingizsiz tasdiqlanmaydi.\n" +
            "• Tasdiqlash — taklifni ishlab chiqish uchun siz bergan ruxsat qaydi.\n" +
            "• Ilova hozircha o‘z Kotlin kodini o‘zi tahrirlay olmaydi.\n" +
            "• Kodni o‘zgartirish, sinash va yangi APK yig‘ish alohida ishlab chiqish jarayonida bajariladi.\n" +
            "• APKni telefonga o‘rnatish qarori ham sizda qoladi.\n\n" +
            "Xavfsizlik: avtomatik kod yozish, avtomatik yangilash va avtomatik o‘rnatish yoqilmagan."
        android.app.AlertDialog.Builder(this)
            .setTitle("SARDOR — ASOSIY BOSHQARUV")
            .setMessage(message)
            .setPositiveButton("TUSHUNDIM", null)
            .show()
    }

    private fun showChangeRequestDialog() {
        val titleInput = EditText(this).apply { hint = "Taklif nomi"; textSize = 15f }
        val detailInput = EditText(this).apply {
            hint = "Nimani o‘zgartirish kerakligini batafsil yozing"
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
            textSize = 14f
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
            addView(titleInput)
            addView(detailInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("YANGI O‘ZGARISH TAKLIFI")
            .setView(box)
            .setPositiveButton("TAKLIFNI SAQLASH") { _, _ ->
                val title = titleInput.text.toString().trim()
                val detail = detailInput.text.toString().trim()
                if (title.isBlank() || detail.isBlank()) {
                    Toast.makeText(this, "Taklif nomi va izohini kiriting", Toast.LENGTH_LONG).show()
                } else {
                    val prefs = getSharedPreferences("arion_development", Context.MODE_PRIVATE)
                    val requests = try {
                        org.json.JSONArray(prefs.getString("change_requests", "[]").orEmpty())
                    } catch (_: Exception) { org.json.JSONArray() }
                    requests.put(JSONObject()
                        .put("id", System.currentTimeMillis().toString())
                        .put("title", title)
                        .put("detail", detail)
                        .put("status", "KUTILMOQDA")
                        .put("created_at", System.currentTimeMillis()))
                    val trimmed = org.json.JSONArray()
                    val start = (requests.length() - 30).coerceAtLeast(0)
                    for (i in start until requests.length()) trimmed.put(requests.getJSONObject(i))
                    prefs.edit().putString("change_requests", trimmed.toString()).apply()
                    Toast.makeText(this, "Taklif saqlandi. Tasdiqlash sizning qo‘lingizda.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("BEKOR", null)
            .show()
    }

    private fun showChangeRequests() {
        val prefs = getSharedPreferences("arion_development", Context.MODE_PRIVATE)
        val requests = try {
            org.json.JSONArray(prefs.getString("change_requests", "[]").orEmpty())
        } catch (_: Exception) { org.json.JSONArray() }
        if (requests.length() == 0) {
            android.app.AlertDialog.Builder(this)
                .setTitle("O‘ZGARISH TAKLIFLARI")
                .setMessage("Hozircha taklif yo‘q. Avval yangi taklif kiriting.")
                .setPositiveButton("YOPISH", null)
                .show()
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        for (i in requests.length() - 1 downTo 0) {
            val item = requests.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val title = item.optString("title", "Nomsiz taklif")
            val detail = item.optString("detail")
            val state = item.optString("status", "KUTILMOQDA")
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(Color.rgb(246, 248, 251), 12)
            }
            card.addView(TextView(this).apply {
                text = title
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(35, 45, 60))
            })
            card.addView(TextView(this).apply {
                text = "$state\n$detail"
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(4), 0, dp(8))
            })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val approve = Button(this).apply {
                text = "TASDIQLASH"
                isEnabled = state == "KUTILMOQDA"
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("TASDIQLAYSIZMI?")
                        .setMessage("Siz ushbu taklifni ishlab chiqish uchun ma’qullaysiz. Bu tugma ilova kodini o‘zi o‘zgartirmaydi.")
                        .setPositiveButton("HA, RUXSAT BERAMAN") { _, _ ->
                            setChangeRequestStatus(id, "TASDIQLANDI")
                            showChangeRequests()
                        }
                        .setNegativeButton("ORTGA", null)
                        .show()
                }
            }
            val reject = Button(this).apply {
                text = "RAD ETISH"
                isEnabled = state == "KUTILMOQDA"
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("TAKLIFNI RAD ETASIZMI?")
                        .setPositiveButton("RAD ETISH") { _, _ ->
                            setChangeRequestStatus(id, "RAD ETILDI")
                            showChangeRequests()
                        }
                        .setNegativeButton("ORTGA", null)
                        .show()
                }
            }
            buttons.addView(approve, LinearLayout.LayoutParams(0, -2, 1f))
            buttons.addView(reject, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(buttons)
            panel.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("TAKLIFLARNI BOSHQARISH")
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("YOPISH", null)
            .show()
    }

    private fun setChangeRequestStatus(requestId: String, newStatus: String) {
        val prefs = getSharedPreferences("arion_development", Context.MODE_PRIVATE)
        val old = try {
            org.json.JSONArray(prefs.getString("change_requests", "[]").orEmpty())
        } catch (_: Exception) { org.json.JSONArray() }
        val updated = org.json.JSONArray()
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (item.optString("id") == requestId) item.put("status", newStatus)
            updated.put(item)
        }
        prefs.edit().putString("change_requests", updated.toString()).apply()
        Toast.makeText(this, "Qaror saqlandi: $newStatus", Toast.LENGTH_SHORT).show()
    }

    private fun sendDevelopmentTask(prompt: String) {
        if (waitingForAnswer) {
            Toast.makeText(this, "Avval joriy javob tugashini kuting", Toast.LENGTH_SHORT).show()
            return
        }
        addMessage("Siz", "🧠 ARION rivojlanish tahlili")
        askBackend(prompt, true)
    }

    private fun showDevelopmentNoteDialog(title: String, key: String, hintText: String) {
        val editor = EditText(this).apply {
            hint = hintText
            minLines = 3
            maxLines = 7
            textSize = 15f
            gravity = Gravity.TOP
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(editor)
            .setPositiveButton("SAQLASH") { _, _ ->
                val value = editor.text.toString().trim()
                if (value.isNotEmpty()) {
                    val prefs = getSharedPreferences("arion_development", Context.MODE_PRIVATE)
                    val old = prefs.getString(key, "").orEmpty()
                    val updated = (old.lines().filter { it.isNotBlank() } + "• $value")
                        .takeLast(20).joinToString("\n")
                    prefs.edit().putString(key, updated).apply()
                    Toast.makeText(this, "Rivojlanish qaydi saqlandi", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("BEKOR", null)
            .show()
    }

    private fun showDevelopmentNotes() {
        val prefs = getSharedPreferences("arion_development", Context.MODE_PRIVATE)
        val learning = prefs.getString("learning_notes", "").orEmpty()
        val feedback = prefs.getString("feedback_notes", "").orEmpty()
        val memory = getSharedPreferences("arion_memory", Context.MODE_PRIVATE)
            .getString("notes", "").orEmpty()
        val content = buildString {
            append("SAQLANGAN BILIMLAR\n")
            append(if (learning.isBlank()) "Hozircha yo‘q" else learning)
            append("\n\nFIKR VA XATOLAR\n")
            append(if (feedback.isBlank()) "Hozircha yo‘q" else feedback)
            append("\n\nSHAXSIY XOTIRA\n")
            append(if (memory.isBlank()) "Hozircha yo‘q" else memory)
        }
        val view = TextView(this).apply {
            text = content
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setTextIsSelectable(true)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("ARION RIVOJLANISH QAYDLARI")
            .setView(ScrollView(this).apply { addView(view) })
            .setNeutralButton("QAYDLARNI TOZALASH") { _, _ ->
                prefs.edit().remove("learning_notes").remove("feedback_notes").apply()
                Toast.makeText(this, "Rivojlanish qaydlari tozalandi", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("YOPISH", null)
            .show()
    }

    private fun showDevelopmentDiagnostics() {
        val prefs = getSharedPreferences("arion_development", Context.MODE_PRIVATE)
        val apiReady = !readApiKey().isNullOrBlank()
        val micReady = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val speechReady = SpeechRecognizer.isRecognitionAvailable(this)
        val learningCount = prefs.getString("learning_notes", "").orEmpty()
            .lines().count { it.trim().startsWith("•") }
        val feedbackCount = prefs.getString("feedback_notes", "").orEmpty()
            .lines().count { it.trim().startsWith("•") }
        val version = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "noma’lum"
        } catch (_: Exception) { "noma’lum" }
        val report = "Ilova versiyasi: $version\n" +
            "Groq API kaliti: " + (if (apiReady) "saqlangan ✓" else "kiritilmagan !") + "\n" +
            "Mikrofon ruxsati: " + (if (micReady) "berilgan ✓" else "berilmagan !") + "\n" +
            "Ovoz aniqlash xizmati: " + (if (speechReady) "mavjud ✓" else "topilmadi !") + "\n" +
            "Saqlangan bilimlar: $learningCount ta\n" +
            "Fikr va xatolar: $feedbackCount ta\n\n" +
            "Eslatma: bu mahalliy tekshiruv. Groq serveriga haqiqiy so‘rov yuborib tekshirish alohida amalga oshiriladi."
        android.app.AlertDialog.Builder(this)
            .setTitle("ARION DIAGNOSTIKASI")
            .setMessage(report)
            .setPositiveButton("YOPISH", null)
            .show()
    }

    // Cheklangan telefon agenti: faqat foydalanuvchi aniq buyurgan xavfsiz Android amallarini taklif qiladi.
    // Qo‘ng‘iroq va SMS avtomatik yuborilmaydi — faqat telefon terish/yozish oynasi ochiladi.
    private fun handleDeviceCommand(raw: String): Boolean {
        val q = raw.trim()
        val lower = q.lowercase()
        var title = ""
        var description = ""
        var launch: (() -> Unit)? = null

        fun openWeb(url: String, label: String) {
            title = "$label ochilsinmi?"
            description = "ARION telefoningizda brauzerni ochadi. Keyingi amalni o‘zingiz bajarasiz."
            launch = { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        }

        when {
            lower in listOf("brauzerni och", "internetni och", "google och", "brauzer och") ->
                openWeb("https://www.google.com", "Google")
            lower in listOf("youtube och", "yutub och", "youtube ni och") ->
                openWeb("https://www.youtube.com", "YouTube")
            lower in listOf("sozlamalarni och", "telefon sozlamalarini och", "nastroykani och") -> {
                title = "Telefon sozlamalari ochilsinmi?"
                description = "ARION Android sozlamalari oynasini ochadi."
                launch = { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
            }
            lower.startsWith("xaritani och") || lower.startsWith("google xarita och") -> {
                val place = q.substringAfter("och", "").trim()
                val uri = if (place.isBlank()) android.net.Uri.parse("geo:0,0")
                    else android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(place))
                title = "Xarita ochilsinmi?"
                description = if (place.isBlank()) "Xarita ilovasi ochiladi." else "Xaritada «$place» qidiriladi."
                launch = { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
            lower.startsWith("raqamga qo'ng'iroq qil ") ||
                lower.startsWith("raqamga qo‘ng‘iroq qil ") ||
                lower.startsWith("telefon qil ") -> {
                val number = q.substringAfterLast(" ").filter { it.isDigit() || it == '+' }
                if (number.length < 7) return false
                title = "Raqam terish oynasi ochilsinmi?"
                description = "$number raqamiga qo‘ng‘iroq qilish oynasi ochiladi. Qo‘ng‘iroqni o‘zingiz tasdiqlaysiz."
                launch = { startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number"))) }
            }
            else -> return false
        }

        val action = launch ?: return false
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(description)
            .setPositiveButton("HA, OCH") { _, _ ->
                try {
                    action()
                    addMessage("ARION", "Bajarildi: kerakli telefon oynasini ochdim. Yakuniy amalni o‘zingiz tasdiqlang.")
                } catch (e: Exception) {
                    addMessage("ARION", "Bu amalni ochib bo‘lmadi: ${e.localizedMessage ?: "mos ilova topilmadi"}.")
                }
            }
            .setNegativeButton("BEKOR", null)
            .show()
        return true
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
            .setTitle("ARION — INTERNET TADQIQOTI")
            .setMessage("ARION internetdan manbalarni qidiradi va natijalarni shu suhbatda tahlil qiladi.")
            .setView(queryInput)
            .setPositiveButton("QIDIRISH VA TAHLIL") { _, _ ->
                val query = queryInput.text.toString().trim()
                if (query.isNotEmpty() && !waitingForAnswer) {
                    addMessage("Siz", "Internetdan qidirib tahlil qil: $query")
                    askBackend("Internetdan qidirib, topilgan manbalar asosida tahlil qil: $query", true)
                }
            }
            .setNegativeButton("BEKOR", null)
            .show()
    }

    // Birinchi haqiqiy internet vositasi: qidiruv natijalari ARION ichida olinadi.
    private fun searchWeb(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val connection = (URL("https://html.duckduckgo.com/html/?q=$encoded").openConnection()
            as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 12000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Qidiruv xizmati HTTP $code")
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val document = Jsoup.parse(html)
            val results = document.select(".result")
            val lines = mutableListOf<String>()
            for (result in results) {
                if (lines.size >= 6) break
                val link = result.selectFirst("a.result__a") ?: continue
                val title = link.text().trim()
                val rawUrl = link.attr("href").trim()
                if (title.isBlank() || rawUrl.isBlank()) continue
                val absolute = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
                val parsed = android.net.Uri.parse(absolute)
                val target = parsed.getQueryParameter("uddg") ?: absolute
                if (!target.startsWith("https://") && !target.startsWith("http://")) continue
                val snippet = result.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
                lines.add("${lines.size + 1}. $title\n${snippet.take(500)}\nManba: $target")
            }
            if (lines.isEmpty()) throw IOException("Qidiruv natijalari olinmadi. Xizmat vaqtincha cheklagan bo‘lishi mumkin.")
            return lines.joinToString("\n\n")
        } finally {
            connection.disconnect()
        }
    }

    private fun shouldSearchWeb(question: String, forceAgent: Boolean): Boolean {
        if (forceAgent) return true
        val q = question.lowercase()
        val triggers = listOf(
            "internetdan", "internetda", "qidir", "izlab top", "topib ber",
            "ish top", "ish qidir", "mijoz top", "xaridor top", "daromad",
            "pul top", "narxi", "narxini", "bugungi", "hozirgi", "yangilik",
            "eng yaxshi", "solishtir", "taqqosla", "kompaniya", "kursi",
            "dollar", "valyuta", "bozor", "e'lon", "elon", "saytlardan",
            "manbalar", "ma'lumot top", "qayerdan sotib", "xizmat top"
        )
        return triggers.any { q.contains(it) }
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
        synchronized(chatHistory) { chatHistory.clear() }
        chat.removeAllViews()
        addMessage("ARION", "Yangi suhbat boshlandi. Sizga qanday yordam beray?")
    }

    private fun sendMessage() {
        val question = input.text.toString().trim()
        if (question.isEmpty() || waitingForAnswer) return
        input.setText("")
        hideKeyboard()
        input.clearFocus()
        addMessage("Siz", question)
        val replyWithMadina = liveMode
        if (replyWithMadina) {
            voicePaused = true
            listening = false
            try { recognizer?.cancel() } catch (_: Exception) {}
        }
        askBackend(question, true)
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
    }

    // API kaliti Android Keystore AES-GCM bilan shifrlanib, faqat shu ilova ichida saqlanadi.
    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "arion_groq_key_v1"
        val existing = ks.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun saveApiKey(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        getSharedPreferences("arion_secure", Context.MODE_PRIVATE).edit()
            .putString("groq_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("groq_key", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun readApiKey(): String? {
        return try {
            val prefs = getSharedPreferences("arion_secure", Context.MODE_PRIVATE)
            val ivText = prefs.getString("groq_iv", null)
            val keyText = prefs.getString("groq_key", null)
            if (ivText.isNullOrBlank() || keyText.isNullOrBlank()) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(keyText, Base64.NO_WRAP)), Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    private fun saveOpenRouterKey(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        getSharedPreferences("arion_secure", Context.MODE_PRIVATE).edit()
            .putString("openrouter_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("openrouter_key", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun readOpenRouterKey(): String? {
        return try {
            val prefs = getSharedPreferences("arion_secure", Context.MODE_PRIVATE)
            val ivText = prefs.getString("openrouter_iv", null)
            val keyText = prefs.getString("openrouter_key", null)
            if (ivText.isNullOrBlank() || keyText.isNullOrBlank()) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(keyText, Base64.NO_WRAP)), Charsets.UTF_8)
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    private fun showOpenRouterKeyDialog(onSaved: (() -> Unit)? = null) {
        val field = EditText(this).apply {
            hint = "sk-or-v1-..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(TextView(this@MainActivity).apply {
                text = "Groq limiti tugaganda zaxira AI ishlashi uchun OpenRouter kalitini kiriting. Bepul model tanlanadi, lekin bepul xizmatlarda ham so‘rov chegaralari bo‘lishi mumkin. Kalit telefonda shifrlanib saqlanadi."
                textSize = 13f
                setTextColor(Color.DKGRAY)
            })
            addView(field)
        }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("ARION — zaxira AI kaliti")
            .setView(box).setPositiveButton("SAQLASH", null).setNegativeButton("KEYINROQ", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().trim()
                if (!value.startsWith("sk-or-") || value.length < 20) {
                    field.error = "OpenRouter kalitini to‘liq kiriting"
                    return@setOnClickListener
                }
                try {
                    saveOpenRouterKey(value)
                    dialog.dismiss()
                    Toast.makeText(this, "Zaxira AI kaliti shifrlab saqlandi", Toast.LENGTH_SHORT).show()
                    onSaved?.invoke()
                } catch (e: Exception) {
                    field.error = "Saqlash xatosi: " + (e.localizedMessage ?: "noma'lum")
                }
            }
        }
        dialog.show()
    }

    private fun showApiKeyDialog(onSaved: (() -> Unit)? = null) {
        val field = EditText(this).apply {
            hint = "gsk_..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(TextView(this@MainActivity).apply {
                text = "Groq API kalitini bir marta kiriting. Kalit telefonda Android Keystore orqali shifrlanib saqlanadi."
                textSize = 13f
                setTextColor(Color.DKGRAY)
            })
            addView(field)
        }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("ARION — API kaliti")
            .setView(box).setPositiveButton("SAQLASH", null).setNegativeButton("BEKOR", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().trim()
                if (!value.startsWith("gsk_") || value.length < 20) {
                    field.error = "Groq API kalitini to‘liq kiriting"
                    return@setOnClickListener
                }
                try {
                    saveApiKey(value)
                    dialog.dismiss()
                    Toast.makeText(this, "API kaliti xavfsiz saqlandi", Toast.LENGTH_SHORT).show()
                    onSaved?.invoke()
                } catch (e: Exception) {
                    field.error = "Saqlash xatosi: " + (e.localizedMessage ?: "noma'lum")
                }
            }
        }
        dialog.show()
    }

    private class GroqApiException(val statusCode: Int, detail: String) : Exception(detail)

    // Bitta modeldan ikki bosqichda foydalanamiz: avval qisqa qaror kartasi, keyin foydalanuvchiga javob.
    // Bu yashirin fikr zanjiri emas; faqat vazifa, faktlar, noaniqlik va xavfsiz keyingi qadamlar.
    private fun groqCompletion(apiKey: String, payload: JSONObject): JSONObject {
        val conn = URL(groqUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 90000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer " + apiKey)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = JSONObject(response.ifBlank { "{}" })
            if (code !in 200..299) {
                val detail = json.optJSONObject("error")?.optString("message")
                    ?: json.optString("message", response.take(500))
                throw GroqApiException(code, detail)
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun openRouterCompletion(apiKey: String, payload: JSONObject): JSONObject {
        val conn = URL(openRouterUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 90000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer " + apiKey)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("HTTP-Referer", "https://github.com/wolkwomen-ai/ORION-AI")
            conn.setRequestProperty("X-Title", "ARION AI")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = JSONObject(response.ifBlank { "{}" })
            if (code !in 200..299) {
                val detail = json.optJSONObject("error")?.optString("message")
                    ?: json.optString("message", response.take(500))
                throw GroqApiException(code, "OpenRouter: " + detail)
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    // Groq cheklov yoki server xatosi qaytarsa, foydalanuvchi oldindan saqlagan OpenRouter zaxirasiga o‘tamiz.
    private fun aiCompletion(groqKey: String, payload: JSONObject): JSONObject {
        try {
            return groqCompletion(groqKey, payload)
        } catch (e: GroqApiException) {
            if (e.statusCode !in listOf(408, 429, 500, 502, 503, 504)) throw e
            val backupKey = readOpenRouterKey() ?: throw e
            val backupPayload = JSONObject(payload.toString()).put("model", openRouterFreeModel)
            return openRouterCompletion(backupKey, backupPayload)
        }
    }

    private fun askBackend(question: String, speakAnswer: Boolean) {
        if (handleDeviceCommand(question)) return
        val apiKey = readApiKey()
        if (apiKey.isNullOrBlank()) {
            showApiKeyDialog { askBackend(question, speakAnswer) }
            return
        }
        waitingForAnswer = true
        status.text = "ARION vaziyatni tahlil qilmoqda…"
        val activeAgentMode = agentMode
        thread(name = "arion-cognitive-core") {
            var answer: String
            var needsOpenRouterKey = false
            try {
                val webResearch = if (shouldSearchWeb(question, activeAgentMode)) {
                    runOnUiThread { if (!isFinishing) status.text = "Internetdan manbalar qidirilmoqda…" }
                    try {
                        searchWeb(question)
                    } catch (searchError: Exception) {
                        "INTERNET QIDIRUVI XATOSI: ${searchError.localizedMessage ?: searchError.javaClass.simpleName}. Qidiruv bajarilmadi; natijalarni topdim deb ko‘rsatmang."
                    }
                } else ""
                val baseSystemPrompt = """Siz ARION nomli aqlli yordamchisiz. Foydalanuvchiga tabiiy, ravon va tushunarli o‘zbek tilida, hurmat bilan javob bering. Imlo va tinish belgilariga qat’iy rioya qiling: o‘zbek lotin alifbosidagi o‘, g‘, sh, ch harflarini to‘g‘ri yozing; so‘zlarni buzib yoki talaffuzga qarab xato yozmang; gaplarni grammatik jihatdan to‘g‘ri tuzing. Javobni yuborishdan oldin imlo, kelishik qo‘shimchalari, vergul va nuqtalarni tekshiring. Qisqa va aniq yozing, keraksiz takrorlardan qoching. Ovozda o‘qishga qulay bo‘lishi uchun odatda sodda gaplar va oddiy ro‘yxatlardan foydalaning; foydalanuvchi so‘ramasa, jadval, murakkab Markdown, kod bloklari va ortiqcha belgilarni ishlatmang. Raqam, sana va qisqartmalarni imkon qadar aniq va tabiiy ifodalang. Har bir javobni yuborishdan oldin imlo va mazmunni tekshiring. O‘zingizdan yangi yoki buzilgan so‘z yasamang. O‘zbek lotin yozuvidagi o‘ va g‘ harflarini izchil yozing. Suhbatdoshingiz ismini o‘zi aytsa, shu suhbat davomida aynan o‘sha ism bilan murojaat qiling; ism aytilmasa, ismni taxmin qilmang.
INSONDEK SUHBAT VA MASLAHAT: suhbatni davom etayotgan muloqot deb tushuning; oldingi maqsad, vaziyat, cheklov va tafsilotlarni hisobga oling. Tabiiy, samimiy, xotirjam va hurmatli ohangda gapiring. Hissiyotlarni sezsangiz, qisqa hamdardlik bildiring, ammo insoniy ong yoki haqiqiy his-tuyg‘ularga egaman deb da’vo qilmang. Maslahatda muammoni aniqlang, 2–3 amaliy yo‘lni foyda-xatarlari bilan solishtiring, eng maqbulini sabab bilan tavsiya qiling va birinchi qadamni ayting. Oddiy savolga qisqa javob bering. Muhim ma’lumot yetishmasa, faqat eng zarur bitta savolni bering. Foydalanuvchi xato qilsa, muloyimlik bilan tushuntiring; ko‘r-ko‘rona ma’qullamang. Fakt, taxmin va tavsiyani ajrating; bilmagan narsani ochiq ayting, soxta manba yoki bajarilmagan ishni to‘qimang. Sog‘liq, huquq, moliya va xavfsizlikda ehtiyotkor bo‘ling."""
                val memoryNotes = getSharedPreferences("arion_memory", Context.MODE_PRIVATE)
                    .getString("notes", "").orEmpty().take(3000)
                val developmentPrefs = getSharedPreferences("arion_development", Context.MODE_PRIVATE)
                val learningNotes = developmentPrefs.getString("learning_notes", "").orEmpty().take(3000)
                val feedbackNotes = developmentPrefs.getString("feedback_notes", "").orEmpty().take(3000)
                val savedContext = buildString {
                    if (memoryNotes.isNotBlank()) append("\n\nFoydalanuvchi saqlagan xotira:\n").append(memoryNotes)
                    if (learningNotes.isNotBlank()) append("\n\nFoydalanuvchi o‘rgatgan bilim va qoidalar:\n").append(learningNotes)
                    if (feedbackNotes.isNotBlank()) append("\n\nFoydalanuvchi fikrlari va tuzatishlar:\n").append(feedbackNotes)
                }
                val agentInstructions = if (activeAgentMode) {
                    "\n\nAGENT REJIMI: vazifani maqsad, bosqichlar, kerakli vositalar va tekshiruv mezonlariga ajrating. ARIONda amalda ulanmagan vosita yoki tashqi amalni bajardim deb aytmang. Tashqi, xavfli yoki qaytarib bo‘lmaydigan harakatdan oldin foydalanuvchining aniq roziligini oling. O‘zingiz bajara olmaydigan qadamlarni foydalanuvchiga aniq tushuntiring."
                } else ""

                val historySnapshot = synchronized(chatHistory) {
                    chatHistory.takeLast(10).map { JSONObject(it.toString()) }
                }

                // 1-bosqich: vaziyatni ixcham va amaliy shaklda xaritalash.
                val plannerMessages = org.json.JSONArray()
                plannerMessages.put(JSONObject().put("role", "system").put(
                    "content",
                    """Siz ARIONning vaziyatni anglash va rejalashtirish modulisisiz. Foydalanuvchiga javob yozmang va batafsil ichki fikrlash zanjirini chiqarmang. Faqat qisqa operatsion qaror kartasini tuzing: foydalanuvchi maqsadi, suhbatdan ma’lum faktlar, taxminlar, yetishmayotgan muhim ma’lumot, javob strategiyasi, xavf yoki ruxsat talab qiladigan harakatlar. Oldingi suhbat bilan bog‘lang. Faktni taxmindan ajrating. Oddiy savolda ortiqcha murakkablashtirmang. 120 so‘zdan oshmang. O‘zbek tilida oddiy matn yoki ixcham JSON qaytaring."""
                ))
                if (webResearch.isNotBlank()) {
                    plannerMessages.put(JSONObject().put("role", "system").put("content",
                        "TASHQI INTERNET QIDIRUVI NATIJALARI. Faktlarni shu natijalarga tayangan holda baholang; manbalarni uydirmang.\n" + webResearch))
                }
                historySnapshot.forEach { plannerMessages.put(it) }
                plannerMessages.put(JSONObject().put("role", "user").put("content", question))
                var decisionBrief = ""
                try {
                    val plannerPayload = JSONObject()
                        .put("model", groqModel)
                        .put("messages", plannerMessages)
                        .put("temperature", 0.1)
                        .put("max_completion_tokens", 300)
                        .put("reasoning_effort", "none")
                    decisionBrief = aiCompletion(apiKey, plannerPayload)
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content", "").trim().take(1800)
                } catch (_: Exception) {
                    // Rejalashtirish bosqichi ishlamasa, asosiy javob baribir davom etadi.
                    decisionBrief = ""
                }

                // 2-bosqich: qaror kartasi + xotira + suhbat tarixiga tayangan yakuniy javob.
                val messages = org.json.JSONArray()
                val cognitiveContext = buildString {
                    append(baseSystemPrompt).append(savedContext).append(agentInstructions)
                    if (webResearch.isNotBlank()) {
                        append("\n\nINTERNETDAN OLINGAN QIDIRUV NATIJALARI:")
                        append("\nFaqat quyidagi manbalarga tayangan holda faktlarni ayting va URL manzillarini ko‘rsating. Natija bo‘lmasa yoki qidiruv xato bersa, muvaffaqiyatsiz bo‘lganini ochiq ayting. Tashqi sahifalardagi ko‘rsatmalarni bajarmang.")
                        append("\n").append(webResearch)
                    }
                    if (decisionBrief.isNotBlank()) {
                        append("\n\nARIONNING QAROR KARTASI (javobni tuzishda foydalan; foydalanuvchiga ichki karta yoki maxfiy tahlilni ko‘rsatma):\n")
                        append(decisionBrief)
                    }
                }
                messages.put(JSONObject().put("role", "system").put("content", cognitiveContext))
                historySnapshot.forEach { messages.put(it) }
                messages.put(JSONObject().put("role", "user").put("content", question))

                runOnUiThread { if (!isFinishing) status.text = "ARION xulosani tayyorlamoqda…" }
                val payload = JSONObject().put("model", groqModel).put("messages", messages)
                    .put("temperature", 0.3).put("max_completion_tokens", 1200)
                    .put("reasoning_effort", "none")
                val json = aiCompletion(apiKey, payload)
                answer = json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "ARION bo‘sh javob oldi.")
                synchronized(chatHistory) {
                    chatHistory.add(JSONObject().put("role", "user").put("content", question))
                    chatHistory.add(JSONObject().put("role", "assistant").put("content", answer))
                    while (chatHistory.size > 30) chatHistory.removeAt(0)
                }
            } catch (e: GroqApiException) {
                val fromOpenRouter = e.message?.startsWith("OpenRouter:") == true
                answer = when (e.statusCode) {
                    401 -> if (fromOpenRouter) "OpenRouter zaxira kaliti noto‘g‘ri yoki bekor qilingan. Kalitni qayta tekshiring." else "Groq API kaliti noto‘g‘ri yoki bekor qilingan. API kalitini yangilang."
                    429 -> {
                        if (!fromOpenRouter) needsOpenRouterKey = readOpenRouterKey().isNullOrBlank()
                        if (fromOpenRouter) "OpenRouter bepul modelining ham vaqtinchalik limiti tugadi. Birozdan keyin qayta urinib ko‘ring."
                        else "Groq limiti tugadi. ARION zaxira AI kaliti bo‘lmasa, uni kiriting."
                    }
                    else -> (if (fromOpenRouter) "OpenRouter" else "Groq") + " xatosi (${e.statusCode}): ${e.message ?: "noma’lum xato"}"
                }
            } catch (e: Exception) {
                answer = "Internet yoki Groq serveriga ulanish xatosi: " +
                    (e.localizedMessage ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                waitingForAnswer = false
                status.text = ""
                addMessage("ARION", answer)
                if (needsOpenRouterKey) showOpenRouterKeyDialog { askBackend(question, speakAnswer) }
                if (speakAnswer) speak(speechFriendlyText(answer))
                else if (liveMode) resumeLiveListening()
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
                            updateLiveTranscript(phrase)
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
                override fun onPartialResults(partialResults: Bundle?) {
                    if (liveMode && !waitingForAnswer) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        if (partial.isNotBlank()) {
                            // Gapirayotgan paytda matn tanilgan bo'laklar bilan yangilanadi.
                            updateLiveTranscript(partial)
                        }
                    }
                }
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
        input.clearFocus()
        showVoicePanel()
        resumeLiveListening()
    }

    private fun showVoicePanel() {
        // Katta ovozli ekran: chat yashiriladi, markazda to'liq animatsiyali A ko'rinadi.
        scroll.visibility = View.GONE

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Ovoz animatsiyasi ortidagi to'rtburchak fon olib tashlandi.
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val voiceOrb = OrbView(this).apply {
            minimalMode = false
            contentDescription = "ARION animatsiyali A logotipi — pastga surib kichraytiring"
        }
        var dragStartY = 0f
        voiceOrb.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val movedDown = event.rawY - dragStartY
                    if (movedDown > dp(55)) collapseVoicePanel()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        panel.addView(voiceOrb, LinearLayout.LayoutParams(dp(248), dp(248)).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(10)
        })

        // Jonli suhbatda tanilgan nutq ovozli ekranning o'zida ko'rinadi.
        liveTranscriptView = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(Color.rgb(35, 35, 35))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            minLines = 1
            maxLines = 3
            visibility = View.GONE
        }
        panel.addView(liveTranscriptView, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(2)
            bottomMargin = dp(8)
        })

        panel.addView(TextView(this).apply {
            text = "Gapiring — ARION sizni tinglayapti"
            textSize = 13f
            setTextColor(Color.rgb(65, 80, 100))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))

        voicePanel = panel
        page.addView(panel, 1, LinearLayout.LayoutParams(-1, 0, 1f))

        plusButton.visibility = View.VISIBLE
        micButton.visibility = View.VISIBLE
        micButton.text = "Ⅱ"
        micButton.contentDescription = "Ovozli tinglashni pauza qilish"
        micButton.setOnClickListener {
            val player = edgePlayer
            if (player != null && runCatching { player.isPlaying }.getOrDefault(false)) {
                runCatching { player.stop(); player.release() }
                edgePlayer = null
                status.text = "Madina ovozi to'xtatildi"
                voicePaused = false
                resumeLiveListening()
            } else if (listening) {
                voicePaused = true
                listening = false
                try { recognizer?.cancel() } catch (_: Exception) {}
                micButton.text = "🎙"
                micButton.contentDescription = "Ovozli tinglashni davom ettirish"
                status.text = "Ovozli tinglash pauzada"
            } else if (!waitingForAnswer) {
                hideKeyboard()
                input.clearFocus()
                voicePaused = false
                resumeLiveListening()
            }
        }
        sendButton.text = if (input.text.toString().isNotBlank()) "↑" else "×"
        sendButton.contentDescription = if (input.text.toString().isNotBlank()) "Yuborish" else "Ovozli suhbatni yopish"
        sendButton.setOnClickListener {
            if (input.text.toString().trim().isNotEmpty()) sendMessage() else closeVoiceMode()
        }
        status.text = "Sizni tinglayapman…"
    }

    // A logotipini pastga surish: jonli ovoz davom etadi, chat va yozish paneli qaytadi.
    private fun collapseVoicePanel() {
        if (!liveMode || collapsedVoiceView != null) return
        voicePanel?.let { page.removeView(it) }
        voicePanel = null
        liveTranscriptView = null
        collapsedVoiceView?.let { page.removeView(it) }
        collapsedVoiceView = null
        scroll.visibility = View.VISIBLE

        val compact = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            contentDescription = "ARION ovozli suhbatini kattalashtirish"
        }
        val smallA = OrbView(this).apply {
            minimalMode = false
            contentDescription = "ARION animatsiyali A — ovozli suhbat davom etmoqda"
        }
        compact.addView(smallA, LinearLayout.LayoutParams(dp(64), dp(64)))
        compact.setOnClickListener { expandVoicePanel() }
        collapsedVoiceView = compact
        page.addView(compact, page.indexOfChild(status) + 1,
            LinearLayout.LayoutParams(-1, dp(72)))
        status.text = "Madina bilan ovozli suhbat davom etmoqda"
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun expandVoicePanel() {
        collapsedVoiceView?.let { page.removeView(it) }
        collapsedVoiceView = null
        if (liveMode) showVoicePanel()
    }

    private fun updateLiveTranscript(text: String) {
        if (text.isBlank()) return
        val voiceText = liveTranscriptView
        if (liveMode && voicePanel != null && voiceText != null) {
            // Ovozli ekran ochiq bo'lsa, tanilgan gap A animatsiyasi ostida ko'rinadi.
            voiceText.text = text
            voiceText.visibility = View.VISIBLE
            return
        }

        // Kichraytirilgan ovozli rejimda matn chat tarixida ko'rsatiladi.
        var bubble = liveTranscriptView
        if (bubble == null || bubble.parent == null) {
            bubble = TextView(this).apply {
                textSize = 16f
                setTextColor(Color.rgb(35, 35, 35))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setTextIsSelectable(true)
                background = rounded(Color.rgb(240, 240, 240), 20)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(bubble, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.78f).toInt(), -2))
            }
            chat.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
            liveTranscriptView = bubble
        }
        bubble.text = text
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun resumeLiveListening() {
        if (!liveMode || voicePaused || waitingForAnswer || listening || isFinishing) return
        if (input.hasFocus()) {
            micButton.text = "🎙"
            micButton.contentDescription = "Ovozli tinglashni davom ettirish"
            status.text = "Matn yozishingiz mumkin — tugatgach mikrofonni bosing"
            return
        }
        if (recognizer == null && !ensureRecognizer()) return
        status.text = "Sizni tinglayapman…"
        voicePaused = false
        micButton.text = "Ⅱ"
        micButton.contentDescription = "Ovozli tinglashni pauza qilish"
        try { recognizer?.startListening(speechIntent()) }
        catch (_: Exception) {
            micButton.text = "🎙"
            micButton.contentDescription = "Ovozli tinglashni davom ettirish"
            status.text = "Mikrofonni qayta yoqish uchun 🎙 bosing"
        }
    }

    // TTS matnidan Markdown belgilarini olib tashlaymiz; ekrandagi javob o‘zgarmaydi.
    private fun speechFriendlyText(text: String): String {
        return text
            .replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
            .replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")
            .replace(Regex("""\*\*(.*?)\*\*"""), "$1")
            .replace(Regex("""__(.*?)__"""), "$1")
            .replace(Regex("""(?m)^\s*[-*+]\s+"""), "")
            .replace(Regex("""(?m)^\s*\d+[.)]\s+"""), "")
            .replace("`", "")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
    private fun speak(text: String) {
        status.text = "Madina ovozi tayyorlanmoqda…"
        thread(name = "arion-madina-tts") {
            try {
                val audio = EdgeTtsClient.synthesize(text)
                val file = File(cacheDir, "arion_madina_once.mp3")
                FileOutputStream(file).use { it.write(audio) }
                val player = MediaPlayer()
                player.setDataSource(file.absolutePath)
                player.setOnCompletionListener { finished ->
                    finished.release()
                    if (edgePlayer === finished) edgePlayer = null
                    file.delete()
                    if (liveMode && !waitingForAnswer) {
                        micButton.text = "🎙"
                        resumeLiveListening()
                    }
                }
                player.setOnErrorListener { failed, _, _ ->
                    failed.release()
                    if (edgePlayer === failed) edgePlayer = null
                    file.delete()
                    runOnUiThread {
                        if (liveMode) {
                            status.text = "Madina ovozi ishlamadi. Qayta urinib ko‘ring."
                            if (!waitingForAnswer) resumeLiveListening()
                        } else {
                            status.text = "Madina ovozi ishlamadi. Internetni tekshiring."
                        }
                    }
                    true
                }
                player.prepare()
                runOnUiThread {
                    if (isFinishing) {
                        player.release()
                        file.delete()
                    } else {
                        edgePlayer?.let { old -> runCatching { old.stop(); old.release() } }
                        edgePlayer = player
                        status.text = "Madina gapiryapti…"
                        player.start()
                    }
                }
            } catch (error: Exception) {
                val detail = (error.message ?: error.javaClass.simpleName)
                    .replace(Regex("\\s+"), " ")
                    .take(150)
                runOnUiThread {
                    val message = "Madina ulanish xatosi: $detail"
                    status.text = message
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    if (liveMode && !waitingForAnswer) resumeLiveListening()
                }
            }
        }
    }

    private fun closeVoiceMode() {
        liveMode = false
        voicePaused = false
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        edgePlayer?.runCatching { stop(); release() }
        edgePlayer = null

        voicePanel?.let { page.removeView(it) }
        voicePanel = null
        scroll.visibility = View.VISIBLE
        plusButton.visibility = View.VISIBLE
        micButton.visibility = View.VISIBLE
        micButton.text = "🎙"
        micButton.contentDescription = "Ovozli matn kiritish"
        micButton.setOnClickListener { startDictation() }
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

    override fun onDestroy() {
        liveMode = false
        try { recognizer?.destroy() } catch (_: Exception) {}
        edgePlayer?.runCatching { stop(); release() }
        edgePlayer = null

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

            // Live voice mode: A belgisi puls va aylanuvchi halqalarsiz.
            val pulse = if (minimalMode) 1f else
                1f + 0.025f * kotlin.math.sin(Math.toRadians(rotation.toDouble())).toFloat()
            val s = scale * pulse

            // A belgisi atrofidagi ikkita orbital halqa barcha rejimlarda ko'rinadi.
            // Logotip orqasidagi dumaloq fon/yorug'lik olib tashlandi.
            // A belgisi va uning aylanuvchi orbital chiziqlari saqlanadi.

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

            // Orbital halqalar jonli ovoz rejimida ham uzluksiz aylanadi.
            rotation = (rotation + 0.72f + lastRms.coerceIn(0f, 12f) * 0.035f) % 360f
            postInvalidateDelayed(32)
        }
    }
}
