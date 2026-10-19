package ca.heretic.mobile

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ca.heretic.core.GenerationSettings
import ca.heretic.data.SettingsStore
import java.io.File
import android.provider.DocumentsContract
import ca.heretic.core.DiscoveredModel
import ca.heretic.data.ModelCatalog
import ca.heretic.core.Conversation
import ca.heretic.core.Message
import ca.heretic.data.ChatStore
import ca.heretic.data.ModelStore
import ca.heretic.inference.LlamaEngine
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ChatState(
    val id: String = UUID.randomUUID().toString(),
    val messages: List<Message> = emptyList(),
    val chats: List<Conversation> = emptyList(),
    val model: String = "Aucun modèle", val hasModel: Boolean = false,
    val settings: GenerationSettings = GenerationSettings(),
    val canStop: Boolean = true,
    val localModels: List<DiscoveredModel> = emptyList(),
    val catalogReady: Boolean = false,
    val hasFolder: Boolean = false,
    val busy: Boolean = true, val status: String = "Ouverture de l’historique…"
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ChatStore(application)
    private val models = ModelStore(application)
    private val catalog = ModelCatalog(application)
    private val preferences = SettingsStore(application)
    var modelToMove: DiscoveredModel? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val worker = executor.asCoroutineDispatcher()
    private val stopped = AtomicBoolean(false)
    @Volatile private var engine: LlamaEngine? = null
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch(worker) {
            try {
                val chats = store.read()
                mutable.update { it.copy(chats = chats, settings = preferences.read(), model = models.name, hasModel = models.path != null, hasFolder = catalog.folder != null,
                    busy = false, status = "Prêt • 100 % local") }
            } catch (error: Exception) {
                mutable.update { it.copy(model = models.name, hasModel = models.path != null, hasFolder = catalog.folder != null, busy = false, status = "Historique illisible : ${error.message}") }
            }
        }
    }
    fun newChat() { if (!state.value.busy) mutable.update { it.copy(id = UUID.randomUUID().toString(), messages = emptyList(), status = "Nouvelle discussion") } }
    fun select(chat: Conversation) { if (!state.value.busy) mutable.update { it.copy(id = chat.id, messages = chat.messages, status = "Discussion restaurée") } }
    fun deleteCurrent() {
        if (state.value.busy) return
        val current = state.value
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch(worker) {
            try {
                val chats = current.chats.filter { it.id != current.id }
                store.write(chats)
                mutable.update { it.copy(id = UUID.randomUUID().toString(), messages = emptyList(), chats = chats, status = "Discussion supprimée") }
            } catch (error: Exception) { report(error) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun importModel(uri: Uri) {
        if (state.value.busy) return
        stopped.set(false)
        mutable.update { it.copy(busy = true, status = "Copie du GGUF dans l’espace privé…") }
        viewModelScope.launch(worker) {
            try {
                // Release the old mmap before replacing the file.
                engine?.close(); engine = null
                val name = models.import(uri) { stopped.get() }
                mutable.update { it.copy(model = name, hasModel = true, status = "Modèle importé • prêt à charger") }
            } catch (error: Exception) { report(error) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun scanModels(folder: Uri? = null) {
        if (state.value.busy) return
        stopped.set(false)
        mutable.update { it.copy(busy = true, catalogReady = false, status = "Recherche des GGUF dans le dossier autorisé…") }
        viewModelScope.launch(worker) {
            try {
                if (folder != null) {
                    catalog.remember(folder)
                    mutable.update { it.copy(hasFolder = true, localModels = emptyList()) }
                }
                val found = catalog.scan { stopped.get() }
                mutable.update { it.copy(localModels = found, catalogReady = true,
                    status = "Recherche terminée : ${found.size} fichier(s) GGUF trouvé(s)") }
            } catch (error: Exception) { report(error) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun acknowledgeCatalog() { mutable.update { it.copy(catalogReady = false) } }
    fun useLocalModel(model: DiscoveredModel) {
        if (!state.value.busy) importModel(catalog.uri(model))
    }
    fun saveSettings(settings: GenerationSettings) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch(worker) {
            try { preferences.write(settings); mutable.update { it.copy(settings = settings, status = "Réglages enregistrés") } }
            catch (error: Exception) { report(error) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun changeLocalFile(action: String, model: DiscoveredModel, name: String = "", destination: Uri? = null) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, canStop = false, status = "Modification du fichier sélectionné…") }
        viewModelScope.launch(worker) {
            try {
                when (action) {
                    "rename" -> catalog.rename(model, name)
                    "delete" -> catalog.delete(model)
                    "move" -> catalog.move(model, destination ?: error("Destination absente."))
                    else -> error("Action inconnue.")
                }
                // The cached IDs may be invalid after rename/move. Never offer stale files.
                mutable.update { it.copy(localModels = emptyList(), status = "Fichier modifié • copie active inchangée") }
                val found = catalog.scan { false }
                mutable.update { it.copy(localModels = found, catalogReady = true) }
            } catch (error: Exception) { report(error) }
            finally { mutable.update { it.copy(busy = false, canStop = true) } }
        }
    }
    fun convertActive(destination: Uri, format: Int) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, canStop = false, status = "Conversion GGUF en cours • opération non interruptible…") }
        viewModelScope.launch(worker) {
            var temporary: File? = null
            var success = false
            try {
                val input = File(models.path ?: error("Importez d’abord un modèle."))
                val context = getApplication<Application>()
                require(context.cacheDir.usableSpace > input.length() * 4 + 64L * 1024 * 1024) {
                    "Espace insuffisant pour la conversion (réserve : 4 × la taille du modèle + 64 Mio)."
                }
                engine?.close(); engine = null
                val output = File.createTempFile("quantification-", ".gguf", context.cacheDir).also { temporary = it }
                LlamaEngine().use { it.quantize(input.absolutePath, output.absolutePath, format) }
                context.contentResolver.openOutputStream(destination, "wt")?.use { target ->
                    output.inputStream().use { source -> source.copyTo(target) }
                } ?: error("Impossible d’écrire le fichier de destination.")
                success = true
                mutable.update { it.copy(status = "Copie GGUF convertie et exportée • importez-la pour l’utiliser") }
            } catch (error: Exception) { report(error) }
            catch (error: LinkageError) { mutable.update { it.copy(status = "Convertisseur natif indisponible : ${error.message}") } }
            finally {
                temporary?.delete()
                if (!success) {
                    try { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, destination) }
                    catch (_: Exception) { /* A provider may leave a partial destination file. */ }
                }
                mutable.update { it.copy(busy = false, canStop = true) }
            }
        }
    }
    fun send(text: String) {
        val snapshot = state.value
        if (snapshot.busy || !snapshot.hasModel || text.isBlank()) return
        stopped.set(false)
        val history = snapshot.messages.filter { it.text.isNotBlank() } + Message("user", text.trim())
        mutable.update { it.copy(messages = history + Message("assistant", ""), busy = true, status = "Chargement et lecture du contexte…") }
        viewModelScope.launch(worker) {
            try {
                persist()
                if (!stopped.get()) {
                    val backend = engine ?: LlamaEngine().also { engine = it }
                    backend.prepare()
                    if (stopped.get()) backend.stop()
                    backend.generate(models.path ?: error("Sélectionnez un modèle."), snapshot.settings.messages(history), snapshot.settings) { chunk ->
                        mutable.update { current ->
                            val messages = current.messages.toMutableList()
                            messages[messages.lastIndex] = messages.last().copy(text = messages.last().text + chunk)
                            current.copy(messages = messages, status = "Réponse en cours…")
                        }
                    }
                }
                mutable.update { it.copy(status = if (stopped.get()) "Génération arrêtée" else "Réponse terminée • ${snapshot.settings.maxTokens} tokens maximum") }
            } catch (error: Exception) { report(error) }
            catch (error: LinkageError) { mutable.update { it.copy(status = "Moteur natif indisponible sur cet appareil : ${error.message}") } }
            finally {
                try { persist() } catch (error: Exception) { report(error) }
                mutable.update { it.copy(busy = false) }
            }
        }
    }
    private fun persist() {
        val current = state.value
        val title = current.messages.firstOrNull { it.role == "user" }?.text?.take(60) ?: "Discussion"
        val chat = Conversation(current.id, title, current.messages)
        val chats = listOf(chat) + current.chats.filter { it.id != current.id }
        store.write(chats)
        mutable.update { it.copy(chats = chats) }
    }
    private fun report(error: Exception) { mutable.update { it.copy(status = "Erreur : ${error.message ?: "opération impossible"}") } }
    fun stop() {
        if (!state.value.canStop) return
        stopped.set(true); engine?.stop(); mutable.update { it.copy(status = "Arrêt demandé…") }
    }
    override fun onCleared() {
        stopped.set(true); engine?.stop()
        executor.execute { engine?.close(); engine = null; executor.shutdown() }
        super.onCleared()
    }
}
