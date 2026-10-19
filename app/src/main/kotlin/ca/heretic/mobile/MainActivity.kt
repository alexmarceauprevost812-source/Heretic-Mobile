package ca.heretic.mobile

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()
    private val orange = Color.rgb(255, 152, 0)
    private lateinit var model: TextView
    private lateinit var status: TextView
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var stop: Button
    private lateinit var choose: Button
    private lateinit var fresh: Button
    private lateinit var history: Button
    private lateinit var delete: Button
    private var rendered: List<ca.heretic.core.Message>? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importModel) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun surface(color: Int, border: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(14).toFloat(); if (border) setStroke(dp(1), orange)
    }
    private fun text(value: String, size: Float = 16f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); setPadding(dp(4), dp(6), dp(4), dp(6))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 14f; minHeight = dp(48)
        setTextColor(Color.WHITE); backgroundTintList = ColorStateList.valueOf(Color.rgb(179, 71, 0))
        setOnClickListener { action() }
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun LinearLayout.addEqual(view: View) { addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(dp(16), dp(8), dp(16), dp(8)) }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val edges = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(dp(16) + edges.left, dp(8) + edges.top,
                dp(16) + edges.right, dp(8) + edges.bottom)
            insets
        }
        root.addView(text("HERETIC MOBILE", 24f).apply { setTypeface(typeface, Typeface.BOLD) })
        root.addView(text("Votre IA, sur votre appareil.", 14f))
        model = text("Aucun modèle", 14f).apply { maxLines = 2 }
        root.addView(model)
        val actions = row()
        choose = button("Modèle GGUF") { picker.launch(arrayOf("*/*")) }
        history = button("Historique") { showHistory() }
        actions.addEqual(choose); actions.addEqual(history); root.addView(actions)
        val secondary = row()
        fresh = button("Nouvelle") { vm.newChat() }
        delete = button("Supprimer") {
            AlertDialog.Builder(this).setTitle("Supprimer cette discussion ?").setMessage("Les messages seront supprimés de cet appareil.")
                .setNegativeButton("Annuler", null).setPositiveButton("Supprimer") { _, _ -> vm.deleteCurrent() }.show()
        }
        secondary.addEqual(fresh); secondary.addEqual(delete); root.addView(secondary)
        status = text("Initialisation…", 13f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status)
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(messages) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        input = EditText(this).apply {
            hint = "Écrivez votre message…"; setHintTextColor(Color.WHITE); setTextColor(Color.WHITE); textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2; maxLines = 4; setPadding(dp(12), dp(8), dp(12), dp(8)); background = surface(Color.rgb(18,18,18), true)
            contentDescription = "Message à envoyer"
        }
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        val composer = row()
        send = button("Envoyer") { val value = input.text.toString(); if (value.isNotBlank()) { vm.send(value); input.text.clear() } }
        stop = button("Arrêter") { vm.stop() }
        composer.addEqual(send); composer.addEqual(stop); root.addView(composer)
        setContentView(root)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { vm.state.collect { render(it) } } }
    }
    private fun render(state: ChatState) {
        model.text = getString(R.string.selected_model, state.model)
        status.text = state.status
        choose.isEnabled = !state.busy; history.isEnabled = !state.busy
        fresh.isEnabled = !state.busy; delete.isEnabled = !state.busy && state.messages.isNotEmpty()
        send.isEnabled = !state.busy && state.hasModel; stop.isEnabled = state.busy
        listOf(choose, history, fresh, delete, send, stop).forEach { it.alpha = if (it.isEnabled) 1f else 0.45f }
        input.isEnabled = !state.busy
        if (rendered == state.messages) return
        val nearBottom = scroll.getChildAt(0).height - scroll.height - scroll.scrollY < dp(100)
        val old = rendered
        if (old != null && old.size == state.messages.size && old.isNotEmpty() && old.dropLast(1) == state.messages.dropLast(1)) {
            val bubble = messages.getChildAt(messages.childCount - 1) as LinearLayout
            (bubble.getChildAt(1) as TextView).text = state.messages.last().text.ifEmpty { "…" }
        } else {
            messages.removeAllViews()
            if (state.messages.isEmpty()) messages.addView(text("Commencez ici\n\n1. Choisissez un fichier GGUF.\n2. Écrivez votre message.\n3. La réponse apparaît au fil des tokens.\n\nLes modèles et discussions restent sur cet appareil.", 16f))
            state.messages.forEach { message ->
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8))
                    background = surface(Color.rgb(16,16,16), message.role == "user")
                }
                box.addView(text(if (message.role == "user") "VOUS" else "HERETIC", 12f).apply { setTypeface(typeface, Typeface.BOLD) })
                box.addView(text(message.text.ifEmpty { "…" }).apply { setTextIsSelectable(true) })
                messages.addView(box, LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,dp(6),0,dp(6)) })
            }
        }
        rendered = state.messages
        if (nearBottom || old == null || old.size != state.messages.size) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun showHistory() {
        val chats = vm.state.value.chats
        if (chats.isEmpty()) { Toast.makeText(this, "Aucune discussion enregistrée", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Historique local").setItems(chats.map { it.title }.toTypedArray()) { _, index -> vm.select(chats[index]) }
            .setNegativeButton("Fermer", null).show()
    }
}
