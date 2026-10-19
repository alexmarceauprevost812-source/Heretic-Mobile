package ca.heretic.mobile

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val busy: Boolean = true, val status: String = "Ouverture de l’historique…"
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ChatStore(application)
    private val models = ModelStore(application)
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
                mutable.update { it.copy(chats = chats, model = models.name, hasModel = models.path != null,
                    busy = false, status = "Prêt • 100 % local") }
            } catch (error: Exception) {
                mutable.update { it.copy(model = models.name, hasModel = models.path != null, busy = false, status = "Historique illisible : ${error.message}") }
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
                    backend.generate(models.path ?: error("Sélectionnez un modèle."), history) { chunk ->
                        mutable.update { current ->
                            val messages = current.messages.toMutableList()
                            messages[messages.lastIndex] = messages.last().copy(text = messages.last().text + chunk)
                            current.copy(messages = messages, status = "Réponse en cours…")
                        }
                    }
                }
                mutable.update { it.copy(status = if (stopped.get()) "Génération arrêtée" else "Réponse terminée • 512 tokens maximum") }
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
    fun stop() { stopped.set(true); engine?.stop(); mutable.update { it.copy(status = "Arrêt demandé…") } }
    override fun onCleared() {
        stopped.set(true); engine?.stop()
        executor.execute { engine?.close(); engine = null; executor.shutdown() }
        super.onCleared()
    }
}
