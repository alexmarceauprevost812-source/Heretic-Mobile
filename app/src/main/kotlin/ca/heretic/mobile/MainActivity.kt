package ca.heretic.mobile

import android.app.AlertDialog
import ca.heretic.core.DiscoveredModel
import ca.heretic.core.GenerationSettings
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
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
    private lateinit var local: Button
    private lateinit var options: Button
    private lateinit var fresh: Button
    private lateinit var history: Button
    private lateinit var delete: Button
    private var rendered: List<ca.heretic.core.Message>? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importModel) }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.scanModels(it) } }
    private val movePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val selected = vm.modelToMove
        vm.modelToMove = null
        if (uri != null && selected != null) vm.changeLocalFile("move", selected, destination = uri)
    }
    private val q4Picker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { vm.convertActive(it, 4) } }
    private val q5Picker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { vm.convertActive(it, 5) } }
    private val q8Picker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { vm.convertActive(it, 8) } }
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
        choose = button("Fichier GGUF") { picker.launch(arrayOf("*/*")) }
        history = button("Historique") { showHistory() }
        local = button("IA locales") { showLocalModels() }
        options = button("Options") { showOptions() }
        actions.addEqual(choose); actions.addEqual(local); actions.addEqual(options); root.addView(actions)
        val secondary = row()
        fresh = button("Nouvelle") { vm.newChat() }
        delete = button("Supprimer") {
            AlertDialog.Builder(this).setTitle("Supprimer cette discussion ?").setMessage("Les messages seront supprimés de cet appareil.")
                .setNegativeButton("Annuler", null).setPositiveButton("Supprimer") { _, _ -> vm.deleteCurrent() }.show()
        }
        secondary.addEqual(fresh); secondary.addEqual(history); secondary.addEqual(delete); root.addView(secondary)
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
        options.isEnabled = !state.busy
        local.isEnabled = !state.busy
        choose.isEnabled = !state.busy; history.isEnabled = !state.busy
        fresh.isEnabled = !state.busy; delete.isEnabled = !state.busy && state.messages.isNotEmpty()
        send.isEnabled = !state.busy && state.hasModel; stop.isEnabled = state.busy && state.canStop
        listOf(choose, local, options, history, fresh, delete, send, stop).forEach { it.alpha = if (it.isEnabled) 1f else 0.45f }
        input.isEnabled = !state.busy
        if (state.catalogReady && !state.busy) { vm.acknowledgeCatalog(); showLocalModels() }
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
    private fun showLocalModels() {
        val state = vm.state.value
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.BLACK)
        }
        content.addView(text(if (state.hasModel) "Modèle actif : ${state.model}" else "Aucun modèle importé", 14f))
        content.addView(text("Choisissez un dossier sur cet appareil pour y repérer les GGUF et ses sous-dossiers. Les modèles privés des autres applications restent inaccessibles. Dans Téléchargements, choisissez un sous-dossier ou utilisez Fichier GGUF.", 14f))
        val list = ListView(this).apply {
            setBackgroundColor(Color.BLACK)
            emptyView = null
        }
        val labels = state.localModels.map { model ->
            val size = if (model.size >= 0) Formatter.formatFileSize(this, model.size) else "Taille inconnue"
            "${model.name} • $size\n${model.folder.ifEmpty { "Dossier choisi" }}"
        }
        list.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).also { (it as TextView).setTextColor(Color.WHITE) }
        }
        if (labels.isEmpty()) content.addView(text("Aucun GGUF répertorié. Choisissez un dossier ou actualisez la recherche.", 14f))
        else content.addView(list, LinearLayout.LayoutParams(-1, dp(200)))
        content.addView(text("La sélection copie le fichier dans Heretic-Mobile. La compatibilité sera vérifiée au chargement.", 13f))
        val dialog = AlertDialog.Builder(this).setTitle("IA locales • GGUF").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Choisir dossier") { _, _ -> folderPicker.launch(null) }
            .setNeutralButton("Actualiser", null)
            .setNegativeButton("Fermer", null).create()
        list.setOnItemClickListener { _, _, index, _ -> dialog.dismiss(); showModelActions(state.localModels[index]) }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                isEnabled = state.hasFolder
                setOnClickListener { dialog.dismiss(); vm.scanModels() }
            }
        }
        dialog.show()
    }
    private fun showModelActions(model: DiscoveredModel) {
        AlertDialog.Builder(this).setTitle(model.name)
            .setItems(arrayOf("Lire / utiliser dans le chat", "Renommer le fichier", "Déplacer le fichier", "Supprimer le fichier")) { _, index ->
                when (index) {
                    0 -> vm.useLocalModel(model)
                    1 -> {
                        val name = EditText(this).apply { setText(model.name); setTextColor(Color.WHITE); setSingleLine() }
                        val dialog = AlertDialog.Builder(this).setTitle("Renommer le GGUF source").setView(name)
                            .setNegativeButton("Annuler", null).setPositiveButton("Renommer", null).create()
                        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            if (name.text.isNotBlank()) { vm.changeLocalFile("rename", model, name.text.toString()); dialog.dismiss() }
                        } }
                        dialog.show()
                    }
                    2 -> AlertDialog.Builder(this).setTitle("Déplacer ${model.name} ?")
                        .setMessage("Le fichier source sera déplacé dans le dossier que vous choisirez, auprès du même fournisseur. La copie importée dans Heretic-Mobile reste disponible.")
                        .setNegativeButton("Annuler", null).setPositiveButton("Choisir destination") { _, _ ->
                            vm.modelToMove = model; movePicker.launch(null)
                        }.show()
                    3 -> AlertDialog.Builder(this).setTitle("Supprimer ${model.name} ?")
                        .setMessage("Le fichier original du dossier autorisé sera supprimé. Cette action peut être définitive. La copie déjà importée dans Heretic-Mobile reste disponible.")
                        .setNegativeButton("Annuler", null).setPositiveButton("Supprimer") { _, _ -> vm.changeLocalFile("delete", model) }.show()
                }
            }.setNegativeButton("Fermer", null).show()
    }
    private fun showOptions() {
        AlertDialog.Builder(this).setTitle("Options des IA locales")
            .setItems(arrayOf("Réglages du chat", "Convertir le modèle actif (GGUF)")) { _, index ->
                if (index == 0) showSettings() else showConversion()
            }.setNegativeButton("Fermer", null).show()
    }
    private fun showSettings() {
        val current = vm.state.value.settings
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundColor(Color.BLACK) }
        fun field(label: String, value: String, numeric: Boolean = true): EditText {
            content.addView(text(label, 14f))
            return EditText(this).apply {
                setText(value); setTextColor(Color.WHITE)
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                maxLines = if (numeric) 1 else 4
                content.addView(this)
            }
        }
        val temperature = field("Température • 0 à 2", current.temperature.toString())
        val topP = field("Top-p • 0,05 à 1", current.topP.toString())
        val length = field("Longueur maximale • 16 à 1 024 tokens", current.maxTokens.toString())
        val threads = field("Threads CPU • 1 à 8", current.threads.toString())
        val instruction = field("Consigne de conversation facultative", current.instruction, false)
        val dialog = AlertDialog.Builder(this).setTitle("Réglages du chat").setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                vm.saveSettings(GenerationSettings(temperature.text.toString().replace(',', '.').toFloat(), topP.text.toString().replace(',', '.').toFloat(),
                    length.text.toString().toInt(), threads.text.toString().toInt(), instruction.text.toString()))
                dialog.dismiss()
            } catch (error: IllegalArgumentException) { Toast.makeText(this, error.message ?: "Réglages invalides", Toast.LENGTH_LONG).show() }
        } }
        dialog.show()
    }
    private fun showConversion() {
        if (!vm.state.value.hasModel) { Toast.makeText(this, "Importez d’abord un modèle GGUF.", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this).setTitle("Créer une copie quantifiée")
            .setItems(arrayOf("Q4_K_M • compact", "Q5_K_M • intermédiaire", "Q8_0 • plus volumineux")) { _, index ->
                AlertDialog.Builder(this).setTitle("Convertir le modèle actif ?")
                    .setMessage("Une nouvelle copie GGUF sera créée ; l’original reste intact. Une nouvelle quantification peut réduire la qualité. L’opération exige de la mémoire et de l’espace et ne peut pas être interrompue. Ce n’est pas un réentraînement. Le modèle doit être compatible avec llama.cpp.")
                    .setNegativeButton("Annuler", null).setPositiveButton("Choisir le fichier de sortie") { _, _ ->
                        when (index) {
                            0 -> q4Picker.launch("Heretic-Q4_K_M.gguf")
                            1 -> q5Picker.launch("Heretic-Q5_K_M.gguf")
                            2 -> q8Picker.launch("Heretic-Q8_0.gguf")
                        }
                    }.show()
            }.setNegativeButton("Fermer", null).show()
    }
    private fun showHistory() {
        val chats = vm.state.value.chats
        if (chats.isEmpty()) { Toast.makeText(this, "Aucune discussion enregistrée", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Historique local").setItems(chats.map { it.title }.toTypedArray()) { _, index -> vm.select(chats[index]) }
            .setNegativeButton("Fermer", null).show()
    }
}
