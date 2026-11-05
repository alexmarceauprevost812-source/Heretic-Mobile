package ca.heretic.data

import android.content.Context
import android.util.AtomicFile
import ca.heretic.core.GenerationSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LabProfile(val id: String, val name: String, val model: String, val settings: GenerationSettings)
object SettingsJson {
    fun encode(s: GenerationSettings) = JSONObject().apply {
        put("temperature", s.temperature); put("topP", s.topP); put("maxTokens", s.maxTokens); put("threads", s.threads)
        put("instruction", s.instruction); put("topK", s.topK); put("minP", s.minP); put("repeatPenalty", s.repeatPenalty)
        put("repeatLastN", s.repeatLastN); put("seed", s.seed); put("contextSize", s.contextSize)
        put("language", s.language); put("tone", s.tone); put("specialty", s.specialty)
    }
    fun decode(j: JSONObject) = GenerationSettings(
        temperature = j.getDouble("temperature").toFloat(), topP = j.getDouble("topP").toFloat(),
        maxTokens = j.getInt("maxTokens"), threads = j.getInt("threads"), instruction = j.getString("instruction"),
        topK = j.optInt("topK", 40), minP = j.optDouble("minP", 0.0).toFloat(),
        repeatPenalty = j.optDouble("repeatPenalty", 1.0).toFloat(), repeatLastN = j.optInt("repeatLastN", 64),
        seed = j.optLong("seed", -1), contextSize = j.optInt("contextSize", 2048),
        language = j.optString("language", ""), tone = j.optString("tone", ""), specialty = j.optString("specialty", ""))
}
class LabStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "lab-profiles-v1.json"))
    private val results = AtomicFile(File(context.filesDir, "lab-comparisons-v1.json"))
    fun read(): List<LabProfile> = if (file.baseFile.exists()) parse(file.openRead().bufferedReader().use { it.readText() }) else presets()
    fun export(profiles: List<LabProfile>): String = JSONObject().put("version", 1).put("profiles", JSONArray().apply {
        profiles.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("model", it.model).put("settings", SettingsJson.encode(it.settings))) }
    }).toString(2)
    fun parse(text: String): List<LabProfile> {
        require(text.length <= 1_000_000) { "Fichier de profils trop volumineux." }
        val root = JSONObject(text); require(root.getInt("version") == 1) { "Version de profils inconnue." }
        val a = root.getJSONArray("profiles"); require(a.length() in 1..200) { "De 1 à 200 profils attendus." }
        return (0 until a.length()).map { i -> val j = a.getJSONObject(i)
            val name = j.getString("name"); require(name.isNotBlank() && name.length <= 100) { "Nom de profil invalide." }
            val model = j.optString("model", ""); require(model.length <= 2000)
            LabProfile(j.getString("id"), name, model, SettingsJson.decode(j.getJSONObject("settings"))) }
    }
    private fun write(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try { stream.write(text.toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun save(profiles: List<LabProfile>) { require(profiles.size <= 200) { "200 profils maximum." }; write(file, export(profiles)) }
    fun history(): JSONArray = if (results.baseFile.exists()) JSONArray(results.openRead().bufferedReader().use { it.readText() }) else JSONArray()
    fun record(result: JSONObject) {
        val old = history(); val next = JSONArray()
        for (i in maxOf(0, old.length() - 99) until old.length()) next.put(old.getJSONObject(i))
        next.put(result); write(results, next.toString(2))
    }
    private fun presets() = listOf(
        "Code" to GenerationSettings(temperature = 0.2f, specialty = "Programmation"),
        "Discussion" to GenerationSettings(), "Analyse" to GenerationSettings(temperature = 0.3f, specialty = "Analyse rigoureuse"),
        "Création" to GenerationSettings(temperature = 1.1f),
        "Rapide" to GenerationSettings(maxTokens = 128),
        "Économie de batterie" to GenerationSettings(maxTokens = 128, threads = 2)
    ).map { LabProfile(UUID.randomUUID().toString(), it.first, "", it.second) }
}
