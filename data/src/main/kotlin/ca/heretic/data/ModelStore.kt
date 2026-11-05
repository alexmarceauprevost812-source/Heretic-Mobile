package ca.heretic.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

class ModelStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("model", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "models").apply { mkdirs() }
    private var descriptor: android.os.ParcelFileDescriptor? = null
    private var directUri: String? = null
    val direct: Boolean get() = prefs.contains("uri")
    val selectedUri: String? get() = prefs.getString("sourceUri", null) ?: prefs.getString("uri", null)
    val hasSelection: Boolean get() = direct || privatePath != null
    fun isSource(uri: Uri): Boolean = selectedUri == uri.toString()
    fun release() { descriptor?.close(); descriptor = null; directUri = null }
    private fun openDirect(uri: Uri): android.os.ParcelFileDescriptor {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Fichier inaccessible.")
        try {
            require(android.system.OsConstants.S_ISREG(android.system.Os.fstat(fd.fileDescriptor).st_mode)) { "Lecture directe indisponible." }
            android.system.Os.lseek(fd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
            val magic = ByteArray(4)
            require(android.system.Os.pread(fd.fileDescriptor, magic, 0, 4, 0) == 4 && magic.contentEquals(byteArrayOf(71,71,85,70))) { "Signature GGUF invalide." }
            File("/proc/self/fd/${fd.fd}").inputStream().use { require(it.read() == 71) { "Lecture directe indisponible." } }
            return fd
        } catch (e: Exception) { fd.close(); throw e }
    }
    /** A seekable regular SAF descriptor can be reopened by llama.cpp through procfs without copying. */
    fun select(uri: Uri, cancelled: () -> Boolean): String {
        val fd = try { openDirect(uri) } catch (_: Exception) { return import(uri, cancelled) }
        try {
            val resolver = context.contentResolver
            try { resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (e: SecurityException) {
                val parentGrant = resolver.persistedUriPermissions.any { permission ->
                    permission.isReadPermission && permission.uri.authority == uri.authority &&
                        android.provider.DocumentsContract.isTreeUri(uri) &&
                        runCatching { android.provider.DocumentsContract.getTreeDocumentId(permission.uri) == android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrDefault(false)
                }
                if (!parentGrant) throw e
            }
            var label = "modele.gguf"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) label = it.getString(0) ?: label
            }
            check(!cancelled()) { "Sélection arrêtée." }
            rememberPrivateLabel()
            check(prefs.edit().putString("sourceUri", uri.toString()).putString("uri", uri.toString()).putString("name", label).commit())
            release(); descriptor = fd; directUri = uri.toString()
            return label
        } catch (_: SecurityException) { fd.close(); return import(uri, cancelled) }
        catch (e: Exception) { fd.close(); throw e }
    }
    val size: Long get() = descriptor?.statSize ?: path?.let { File(it).length() } ?: 0L
    val path: String? get() {
        val uri = prefs.getString("uri", null)
        if (uri != null) {
            if (descriptor == null || directUri != uri) { release(); descriptor = openDirect(Uri.parse(uri)); directUri = uri }
            return "/proc/self/fd/${descriptor!!.fd}"
        }
        return privatePath
    }
    private val privatePath: String? get() = prefs.getString("file", null)?.let { File(directory, it) }?.takeIf { it.isFile }?.absolutePath
    val name: String get() = prefs.getString("name", "Aucun modèle") ?: "Aucun modèle"
    private fun rememberPrivateLabel() {
        val filename = prefs.getString("file", null) ?: return
        if (!direct && !prefs.contains("name:$filename")) {
            check(prefs.edit().putString("name:$filename", name).commit()) { "Impossible de conserver le nom du modèle précédent." }
        }
    }

    fun privateModels(): List<Pair<String, String>> = directory.listFiles()?.filter { it.isFile && it.extension == "gguf" }?.map {
        it.name to (prefs.getString("name:${it.name}", null) ?: if (!direct && it.name == prefs.getString("file", null)) name else "Copie ${it.name}")
    }.orEmpty().sortedBy { it.second }
    fun selectPrivate(filename: String): String {
        require(privateModels().any { it.first == filename }) { "Copie privée introuvable." }
        val label = privateModels().first { it.first == filename }.second
        check(prefs.edit().remove("uri").putString("sourceUri", prefs.getString("source:$filename", null)).putString("file", filename).putString("name", label).commit())
        release(); return label
    }
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
            rememberPrivateLabel()
            check(prefs.edit().remove("uri").putString("sourceUri", uri.toString()).putString("name:${target.name}", name).putString("source:${target.name}", uri.toString()).putString("file", target.name).putString("name", name).commit()) { "Impossible d’enregistrer le modèle." }
            release()
            // Retain existing models; selecting or importing never removes user files.
            return name
        } catch (error: Exception) { target.delete(); throw error }
    }
}

