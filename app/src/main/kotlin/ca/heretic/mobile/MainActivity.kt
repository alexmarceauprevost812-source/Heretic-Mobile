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
    private val orange = Color.rgb(255, 102, 0)
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
    private val remoteResult = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let(vm::remoteDownload) }
    private val profileImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importProfiles) }
    private val profileExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportProfiles) }
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
        setTextColor(Color.BLACK); backgroundTintList = ColorStateList.valueOf(orange)
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
        choose = button("Ouvrir un fichier GGUF") { picker.launch(arrayOf("*/*")) }
        history = button("Historique") { showHistory() }
        local = button("IA locales") { showLocalModels() }
        options = button("Options") { showOptions() }
        root.addView(button("Trouver mes IA sur cet appareil") { showLocalModels() }.apply { id = View.generateViewId() })
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
        content.addView(text("Autorisez Documents, un dossier personnel, un sous-dossier de Téléchargements ou une clé USB avec le sélecteur Android. Les dossiers autorisés sont mémorisés. Android peut interdire la racine de Téléchargements. Un modèle dans le stockage privé d’une autre application nécessite une exportation par cette application. Aucune recherche hors des dossiers autorisés.", 14f))
        val list = ListView(this).apply {
            setBackgroundColor(Color.BLACK)
            emptyView = null
        }
        val labels = state.localModels.map { model ->
            val size = if (model.size >= 0) Formatter.formatFileSize(this, model.size) else "Taille inconnue"
            "${model.name} • $size\n${model.folder.ifEmpty { "Dossier choisi" }}\nCompatibilité inconnue • vérifier après sélection"
        }
        list.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).also { (it as TextView).setTextColor(Color.WHITE) }
        }
        if (labels.isEmpty()) content.addView(text("Aucun GGUF répertorié. Choisissez un dossier ou actualisez la recherche.", 14f))
        else content.addView(list, LinearLayout.LayoutParams(-1, dp(200)))
        content.addView(button("Ouvrir un fichier GGUF") { picker.launch(arrayOf("*/*")) })
        content.addView(text("Lecture directe sans copie si le fournisseur Android le permet ; sinon copie privée nécessaire au moteur. Un GGUF n’est pas automatiquement compatible : vérifiez ses informations puis essayez une génération. Aucun modèle n’est déplacé ou supprimé automatiquement.", 13f))
        val dialog = AlertDialog.Builder(this).setTitle("Trouver mes IA sur cet appareil").setView(ScrollView(this).apply { addView(content) })
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
            .setItems(arrayOf("Réglages simples", "Réglages avancés", "Personnalité de l’IA", "Profils personnalisés", "Comparer des configurations", "Résultats des comparaisons", "Informations et compatibilité du modèle", "Décharger le modèle de la RAM", "Créer une copie quantifiée (GGUF)", "LexOS distant • tâches sur PC")) { _, index ->
                when (index) {
                    0 -> showSettings(); 1 -> showSettings(advanced = true); 2 -> showPersonality()
                    3 -> showProfiles(); 4 -> showCompare(); 5 -> showResults()
                    6 -> { vm.inspectModel() }; 7 -> vm.unload(); 8 -> showConversion(); 9 -> showRemote()
                }
            }.setNegativeButton("Fermer", null).show()
    }
    private fun form(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundColor(Color.BLACK)
    }
    private fun field(content: LinearLayout, label: String, value: String, numeric: Boolean = false): EditText {
        content.addView(text(label, 14f))
        return EditText(this).apply {
            setText(value); setTextColor(Color.WHITE)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = if (numeric) 1 else 5; content.addView(this)
        }
    }
    private fun editor(title: String, content: LinearLayout, save: () -> Unit) {
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try { save(); dialog.dismiss() }
            catch (e: IllegalArgumentException) { Toast.makeText(this, e.message ?: "Valeur invalide", Toast.LENGTH_LONG).show() }
        } }; dialog.show()
    }
    private fun showSettings(advanced: Boolean = false) {
        val s = vm.state.value.settings
        val content = form()
        fun num(label: String, value: Any) = field(content, label, value.toString(), true)
        fun EditText.float() = text.toString().replace(',', '.').toFloat()
        fun EditText.int() = text.toString().toInt()
        if (!advanced) {
            val temp = num("Température • 0–2. Plus élevée : davantage de diversité ; 0 : choix le plus probable.", s.temperature)
            val length = num("Réponse • 16–4 096 tokens. Réservée dans le contexte ; une réponse courte économise la batterie.", s.maxTokens)
            editor("Réglages simples", content) { vm.saveSettings(s.copy(temperature = temp.float(), maxTokens = length.int())) }
        } else {
            val p = num("Top-p • 0,05–1. Conserve les candidats couvrant cette probabilité.", s.topP)
            val k = num("Top-k • 0–1 000. Limite les candidats ; 0 désactive ce filtre.", s.topK)
            val min = num("Min-p • 0–1. Écarte les candidats trop faibles par rapport au meilleur ; 0 désactive.", s.minP)
            val penalty = num("Pénalité de répétition • 0,5–2. 1 neutre ; plus de 1 réduit les répétitions.", s.repeatPenalty)
            val window = num("Fenêtre de répétition • 0–8 192 tokens. 0 désactive la pénalité.", s.repeatLastN)
            val seed = num("Graine • -1 aléatoire, sinon 0–4 294 967 294. Reproductibilité sous conditions identiques.", s.seed)
            val context = num("Contexte • 512–16 384 tokens. Inclut consignes, historique et réponse. Plus grand : davantage de RAM ; respecter les limites du modèle.", s.contextSize)
            val threads = num("Threads CPU • 1–8. Plus de threads peut augmenter la chauffe sans accélérer le modèle.", s.threads)
            editor("Réglages avancés", content) { vm.saveSettings(s.copy(topP = p.float(), topK = k.int(), minP = min.float(),
                repeatPenalty = penalty.float(), repeatLastN = window.int(), seed = seed.text.toString().toLong(), contextSize = context.int(), threads = threads.int())) }
        }
    }
    private fun showPersonality() {
        val s = vm.state.value.settings; val content = form()
        content.addView(text("Ces champs composent la consigne système. Ils ne modifient pas les poids. Enregistrez plusieurs profils pour chaque modèle.", 14f))
        val instruction = field(content, "Consignes système • facultatives", s.instruction)
        val language = field(content, "Langue • vide : choix du modèle", s.language)
        val tone = field(content, "Ton • exemple : concis", s.tone)
        val specialty = field(content, "Spécialité • exemple : Kotlin", s.specialty)
        editor("Personnalité de l’IA", content) { vm.saveSettings(s.copy(instruction = instruction.text.toString(), language = language.text.toString(), tone = tone.text.toString(), specialty = specialty.text.toString())) }
    }
    private fun nameProfile(source: ca.heretic.data.LabProfile? = null) {
        val content = form(); val name = field(content, "Nom du profil", source?.let { "${it.name} — copie" } ?: "Mon profil")
        editor("Enregistrer une configuration", content) { vm.saveProfile(name.text.toString(), source) }
    }
    private fun showProfiles() {
        val profiles = vm.state.value.profiles
        AlertDialog.Builder(this).setTitle("Profils • réglages et personnalité")
            .setItems((listOf("Créer avec les réglages actuels", "Exporter tous les profils", "Importer des profils") + profiles.map { "${it.name} • ${it.model.ifEmpty { "Tous modèles" }}" }).toTypedArray()) { _, index ->
                when (index) {
                    0 -> nameProfile(); 1 -> profileExport.launch("Heretic-profils.json"); 2 -> profileImport.launch(arrayOf("application/json", "text/*"))
                    else -> {
                        val profile = profiles[index - 3]
                        AlertDialog.Builder(this).setTitle(profile.name).setMessage("Modèle associé : ${profile.model.ifEmpty { "Tous modèles" }}. Appliquer conserve le modèle actif.")
                            .setPositiveButton("Appliquer") { _, _ -> vm.saveSettings(profile.settings) }
                            .setNeutralButton("Dupliquer") { _, _ -> nameProfile(profile) }
                            .setNegativeButton("Remplacer par les réglages actuels") { _, _ -> vm.updateProfile(profile) }.show()
                    }
                }
            }.setNegativeButton("Fermer", null).show()
    }
    private fun showCompare() {
        val profiles = vm.state.value.profiles; val selected = BooleanArray(profiles.size)
        AlertDialog.Builder(this).setTitle("Configurations à tester successivement")
            .setMultiChoiceItems(profiles.map { it.name }.toTypedArray(), selected) { _, i, checked -> selected[i] = checked }
            .setPositiveButton("Prompt commun") { _, _ ->
                val content = form(); content.addView(text("Même modèle actif, contexte neuf pour chaque profil. Les conversations restent séparées des résultats. Les résultats incluent les réponses partielles et les erreurs.", 14f))
                val prompt = field(content, "Prompt", "")
                editor("Lancer la comparaison", content) {
                    require(selected.any { it }) { "Sélectionnez au moins un profil." }
                    require(prompt.text.isNotBlank() && prompt.text.length <= 32000) { "Prompt : 1 à 32 000 caractères." }
                    vm.compare(prompt.text.toString(), profiles.filterIndexed { i, _ -> selected[i] })
                }
            }.setNegativeButton("Annuler", null).show()
    }
    private fun showResults() {
        val content = form(); content.addView(text(vm.state.value.comparisonHistory, 14f).apply { setTextIsSelectable(true) })
        AlertDialog.Builder(this).setTitle("Comparaisons • 100 résultats récents").setView(ScrollView(this).apply { addView(content) }).setPositiveButton("Fermer", null).show()
    }
    private fun showRemote() {
        val content = form()
        content.addView(text("Facultatif : les tâches lourdes tournent sur votre PC avec le compagnon pc/lexos_lab.py. Le chat local fonctionne hors ligne. Aucun modèle du téléphone n’est envoyé : indiquez un identifiant déjà configuré sur le PC. LoRA, entraînement et ablation exigent une recette et les outils correspondants sur le PC. Une consigne système ne change jamais les poids.", 14f))
        val endpoint = field(content, "Adresse HTTPS du PC", vm.remoteEndpoint)
        val token = field(content, "Jeton d’accès • conservé uniquement pour cette session", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val model = field(content, "Identifiant du modèle sur le PC", "")
        content.addView(button("Connecter et voir les capacités") { vm.connectRemote(endpoint.text.toString(), token.text.toString()) })
        listOf("quantize" to "Quantification sur PC", "lora" to "LoRA sur PC", "train" to "Entraînement sur PC", "ablate" to "Ablation sur PC").forEach { (operation, label) ->
            content.addView(button(label) {
                AlertDialog.Builder(this).setTitle(label).setMessage("Exécuter la recette configurée sur votre PC pour ${model.text} ? Le résultat sera un nouveau fichier. Le modèle source doit rester intact.")
                    .setNegativeButton("Annuler", null).setPositiveButton("Lancer") { _, _ -> vm.remoteJob(operation, model.text.toString()) }.show()
            })
        }
        content.addView(button("Actualiser la progression") { vm.remoteProgress() })
        content.addView(button("Récupérer le résultat") { remoteResult.launch("Heretic-resultat-PC.gguf") })
        AlertDialog.Builder(this).setTitle("LexOS distant • facultatif").setView(ScrollView(this).apply { addView(content) }).setPositiveButton("Fermer", null).show()
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

