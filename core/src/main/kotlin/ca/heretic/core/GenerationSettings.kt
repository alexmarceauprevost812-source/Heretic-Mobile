package ca.heretic.core

data class GenerationSettings(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 512,
    val threads: Int = 4,
    val instruction: String = ""
) {
    init {
        require(temperature.isFinite() && temperature in 0f..2f) { "Température : de 0 à 2." }
        require(topP.isFinite() && topP in 0.05f..1f) { "Top-p : de 0,05 à 1." }
        require(maxTokens in 16..1024) { "Longueur : de 16 à 1 024 tokens." }
        require(threads in 1..8) { "Threads : de 1 à 8." }
        require(instruction.length <= 8000) { "Consigne trop longue (8 000 caractères maximum)." }
    }
    fun messages(history: List<Message>): List<Message> =
        if (instruction.isBlank()) history else listOf(Message("system", instruction.trim())) + history
}
