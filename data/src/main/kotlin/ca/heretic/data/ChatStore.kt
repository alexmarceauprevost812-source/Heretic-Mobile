package ca.heretic.data

import android.content.Context
import android.util.AtomicFile
import ca.heretic.core.Conversation
import ca.heretic.core.Message
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ChatStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "conversations.json"))
    @Synchronized fun read(): List<Conversation> {
        if (!file.baseFile.exists()) return emptyList()
        val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val messages = item.getJSONArray("messages")
            Conversation(item.getString("id"), item.getString("title"),
                (0 until messages.length()).map { j ->
                    val msg = messages.getJSONObject(j)
                    Message(msg.getString("role"), msg.getString("text"))
                })
        }
    }
    @Synchronized fun write(chats: List<Conversation>) {
        val array = JSONArray()
        chats.forEach { chat ->
            val messages = JSONArray()
            chat.messages.forEach { messages.put(JSONObject().put("role", it.role).put("text", it.text)) }
            array.put(JSONObject().put("id", chat.id).put("title", chat.title).put("messages", messages))
        }
        val stream = file.startWrite()
        try { stream.write(array.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
