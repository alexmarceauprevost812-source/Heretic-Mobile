package ca.heretic.core

data class GenerationSettings(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 512,
    val threads: Int = 4,
    val instruction: String = "",
    val topK: Int = 40,
    val minP: Float = 0f,
    val repeatPenalty: Float = 1f,
    val repeatLastN: Int = 64,
    val seed: Long = -1,
    val contextSize: Int = 2048,
    val language: String = "",
    val tone: String = "",
    val specialty: String = ""
) {
    init {
        require(temperature.isFinite() && temperature in 0f..2f) { "Température : de 0 à 2." }
        require(topP.isFinite() && topP in 0.05f..1f) { "Top-p : de 0,05 à 1." }
        require(maxTokens in 16..4096 && maxTokens < contextSize) { "Longueur : de 16 à 4 096 tokens, inférieure au contexte." }
        require(threads in 1..8) { "Threads : de 1 à 8." }
        require(topK in 0..1000) { "Top-k : de 0 à 1 000 (0 désactive)." }
        require(minP.isFinite() && minP in 0f..1f) { "Min-p : de 0 à 1." }
        require(repeatPenalty.isFinite() && repeatPenalty in 0.5f..2f) { "Pénalité : de 0,5 à 2." }
        require(repeatLastN in 0..8192) { "Fenêtre de répétition : de 0 à 8 192." }
        require(seed in -1..4294967294L) { "Graine : -1 aléatoire ou entier de 0 à 4 294 967 294." }
        require(contextSize in 512..16384) { "Contexte : de 512 à 16 384 tokens." }
        require(listOf(language, tone, specialty).all { it.length <= 200 }) { "Personnalité : 200 caractères par champ maximum." }
        require(instruction.length <= 8000) { "Consigne trop longue (8 000 caractères maximum)." }
    }
    fun messages(history: List<Message>): List<Message> {
        val system = listOf(instruction.trim(), language.takeIf { it.isNotBlank() }?.let { "Langue : $it" } ?: "",
            tone.takeIf { it.isNotBlank() }?.let { "Ton : $it" } ?: "",
            specialty.takeIf { it.isNotBlank() }?.let { "Spécialité : $it" } ?: "").filter { it.isNotBlank() }.joinToString("\n")
        return if (system.isBlank()) history else listOf(Message("system", system)) + history
    }
}

