package ca.heretic.data

import android.content.Context
import ca.heretic.core.GenerationSettings

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("generation", Context.MODE_PRIVATE)
    fun read(): GenerationSettings = try {
        GenerationSettings(prefs.getFloat("temperature", 0.7f), prefs.getFloat("topP", 0.9f),
            prefs.getInt("maxTokens", 512), prefs.getInt("threads", 4), prefs.getString("instruction", "") ?: "")
    } catch (_: IllegalArgumentException) { GenerationSettings() }
    fun write(settings: GenerationSettings) {
        check(prefs.edit().putFloat("temperature", settings.temperature).putFloat("topP", settings.topP)
            .putInt("maxTokens", settings.maxTokens).putInt("threads", settings.threads)
            .putString("instruction", settings.instruction).commit()) { "Impossible d’enregistrer les réglages." }
    }
}
