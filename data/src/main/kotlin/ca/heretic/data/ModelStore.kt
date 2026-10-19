package ca.heretic.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

class ModelStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("model", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "models").apply { mkdirs() }
    val path: String? get() = prefs.getString("file", null)?.let { File(directory, it) }?.takeIf { it.isFile }?.absolutePath
    val name: String get() = prefs.getString("name", "Aucun modèle") ?: "Aucun modèle"

    /** Copy a SAF stream into a regular private file, required for native mmap. */
    fun import(uri: Uri, cancelled: () -> Boolean): String {
        var name = "modele.gguf"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) { name = it.getString(0) ?: name; if (!it.isNull(1)) size = it.getLong(1) }
        }
        require(name.endsWith(".gguf", ignoreCase = true)) { "Choisissez un fichier .gguf." }
        require(size < 0 || size + 64L * 1024 * 1024 < directory.usableSpace) { "Espace insuffisant pour copier le modèle." }
        val target = File(directory, "${UUID.randomUUID()}.gguf")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val magic = ByteArray(4)
                    var count = 0
                    while (count < 4) { val n = input.read(magic, count, 4-count); require(n > 0) { "Fichier vide ou incomplet." }; count += n }
                    require(magic.contentEquals(byteArrayOf(71,71,85,70))) { "Ce fichier ne contient pas un modèle GGUF." }
                    output.write(magic)
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        check(!cancelled()) { "Importation arrêtée." }
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(directory.usableSpace > n + 32L * 1024 * 1024) { "Espace de stockage insuffisant." }
                        output.write(buffer, 0, n)
                    }
                }
            } ?: error("Impossible d’ouvrir le fichier sélectionné.")
            check(!cancelled()) { "Importation arrêtée." }
            check(prefs.edit().putString("file", target.name).putString("name", name).commit()) { "Impossible d’enregistrer le modèle." }
            directory.listFiles()?.filter { it != target }?.forEach { it.delete() }
            return name
        } catch (error: Exception) { target.delete(); throw error }
    }
}
