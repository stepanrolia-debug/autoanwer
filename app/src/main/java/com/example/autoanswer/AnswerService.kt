package com.example.autoanswer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class AnswerService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var enabled = false
    private var busy = false
    private var lastHash = 0
    private var pending: Runnable? = null
    private lateinit var btn: TextView
    private lateinit var wm: WindowManager

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        btn = TextView(this).apply {
            text = "OFF"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        paint()
        val lp = WindowManager.LayoutParams(
            150, 150,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        btn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ox = lp.x; oy = lp.y; sx = e.rawX; sy = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (abs(dx) > 15 || abs(dy) > 15) moved = true
                    if (moved) { lp.x = ox + dx; lp.y = oy + dy; wm.updateViewLayout(btn, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) toggle()
            }
            true
        }
        wm.addView(btn, lp)
    }

    private fun paint() {
        btn.text = if (enabled) "ON" else "OFF"
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (enabled) Color.rgb(46, 160, 67) else Color.GRAY)
        }
    }

    private fun toggle() {
        enabled = !enabled
        lastHash = 0
        paint()
        if (enabled) schedule(0)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (!enabled || e == null) return
        if (e.packageName == packageName) return
        schedule(1200)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (::btn.isInitialized) runCatching { wm.removeView(btn) }
        super.onDestroy()
    }

    private fun schedule(delay: Long) {
        pending?.let { main.removeCallbacks(it) }
        pending = Runnable { solve() }.also { main.postDelayed(it, delay) }
    }

    private class Screen {
        val question = StringBuilder()
        val options = ArrayList<Pair<StringBuilder, AccessibilityNodeInfo>>()
    }

    private fun collect(n: AccessibilityNodeInfo?, anc: AccessibilityNodeInfo?, s: Screen) {
        if (n == null || !n.isVisibleToUser) return
        val target = if (n.isClickable) n else anc
        val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) {
            if (target != null) {
                val ex = s.options.firstOrNull { it.second == target }
                if (ex != null) ex.first.append(" ").append(t)
                else s.options.add(StringBuilder(t) to target)
            } else s.question.append(t).append('\n')
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), target, s)
    }

    private fun solve() {
        if (busy || !enabled) return
        val root = rootInActiveWindow ?: return
        val s = Screen()
        collect(root, null, s)
        if (s.options.size < 2 || s.question.isBlank()) return
        val hash = (s.question.toString() + s.options.joinToString { it.first }).hashCode()
        if (hash == lastHash) return
        lastHash = hash
        busy = true

        val q = s.question.toString()
        val opts = s.options.map { it.first.toString() }
        Thread {
            val result = runCatching { ask(q, opts) }
            main.post {
                busy = false
                result.onSuccess { idx ->
                    if (enabled && idx in s.options.indices) click(s.options[idx].second)
                }.onFailure {
                    Toast.makeText(this, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private class HttpErr(val code: Int) : Exception("HTTP $code")

    private fun ask(question: String, options: List<String>): Int {
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val key = prefs.getString("key", "").orEmpty()
        val models = prefs.getString("model", DEFAULT_MODELS).orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (key.isEmpty()) error("не указан ключ Gemini")

        val prompt = buildString {
            append("Ниже текст с экрана: вопрос и варианты ответа.\n\nТекст экрана:\n")
            append(question).append("\nВарианты:\n")
            options.forEachIndexed { i, o -> append("$i. $o\n") }
            append("\nОтветь ТОЛЬКО номером правильного варианта (одно число).")
        }

        var last: Exception? = null
        for (m in models) {
            try {
                return askModel(m, key, prompt)
            } catch (e: HttpErr) {
                last = e
                // лимит / нет такой модели / перегрузка -> пробуем следующую модель
                if (e.code != 429 && e.code != 404 && e.code != 503 && e.code != 500) break
            }
        }
        throw last ?: Exception("нет моделей")
    }

    private fun askModel(model: String, key: String, prompt: String): Int {
        val body = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
        )
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = c.responseCode
        if (code !in 200..299) throw HttpErr(code)
        val text = c.inputStream.bufferedReader().readText()
        val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val answer = buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }
        return Regex("\\d+").find(answer)?.value?.toInt() ?: error("нет ответа")
    }

    companion object {
        const val DEFAULT_MODELS =
            "gemini-3.8-flash,gemini-3.6-flash,gemini-3.5-flash-lite,gemini-3.1-flash-lite"
    }

    private fun click(node: AccessibilityNodeInfo) {
        node.refresh()
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val r = Rect()
        node.getBoundsInScreen(r)
        val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(),
            null, null
        )
    }
}
