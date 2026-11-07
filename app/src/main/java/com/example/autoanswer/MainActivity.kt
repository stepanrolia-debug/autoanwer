package com.example.autoanswer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f }

        val key = EditText(this).apply {
            hint = "Бесплатный ключ Gemini (AIza...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        val model = EditText(this).apply {
            hint = "Модель"
            setText(prefs.getString("model", AnswerService.DEFAULT_MODELS))
        }
        val save = Button(this).apply {
            text = "1. Сохранить"
            setOnClickListener {
                prefs.edit().putString("key", key.text.toString().trim())
                    .putString("model", model.text.toString().trim()).apply()
                Toast.makeText(context, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        }
        val access = Button(this).apply {
            text = "2. Включить службу (Специальные возможности)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val help = label(
            "\n3. Откройте нужное приложение — на экране появится круглая кнопка.\n" +
            "Нажали: ON (зелёная) — программа сама ищет вопросы и нажимает ответы.\n" +
            "Нажали ещё раз: OFF (серая) — ничего не делает.\n" +
            "Кнопку можно перетаскивать.\n\n" +
            "Полностью отключить: Настройки → Специальные возможности → AutoAnswer → выкл."
        )
        listOf(label("Ключ Gemini (aistudio.google.com/apikey):"), key, label("Модели через запятую (если у одной лимит — берётся следующая):"), model, save, access, help).forEach {
            root.addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
