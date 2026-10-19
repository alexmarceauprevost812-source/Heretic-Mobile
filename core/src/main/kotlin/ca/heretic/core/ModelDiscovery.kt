package ca.heretic.core

import java.util.ArrayDeque

/** IDs stay opaque: Android document IDs need not be filesystem paths. */
data class ModelEntry(val id: String, val name: String, val size: Long = -1, val directory: Boolean = false)
data class DiscoveredModel(val id: String, val name: String, val size: Long, val folder: String, val parentId: String = "")

class ModelDiscovery(private val maxEntries: Int = 10_000) {
    fun scan(root: String, children: (String) -> List<ModelEntry>, cancelled: () -> Boolean): List<DiscoveredModel> {
        val pending = ArrayDeque<Pair<String, String>>()
        val visited = mutableSetOf(root)
        val models = mutableListOf<DiscoveredModel>()
        pending.add(root to "")
        var count = 0
        while (pending.isNotEmpty()) {
            check(!cancelled()) { "Recherche arrêtée." }
            val (id, folder) = pending.removeFirst()
            for (entry in children(id)) {
                check(!cancelled()) { "Recherche arrêtée." }
                check(++count <= maxEntries) { "Dossier trop volumineux. Choisissez un sous-dossier contenant vos modèles." }
                if (!visited.add(entry.id)) continue
                if (entry.directory) {
                    pending.add(entry.id to if (folder.isEmpty()) entry.name else "$folder/${entry.name}")
                } else if (entry.name.endsWith(".gguf", ignoreCase = true)) {
                    models.add(DiscoveredModel(entry.id, entry.name, entry.size, folder, id))
                }
            }
        }
        return models.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}
