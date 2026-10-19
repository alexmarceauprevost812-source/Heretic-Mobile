package ca.heretic.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import ca.heretic.core.DiscoveredModel
import ca.heretic.core.ModelDiscovery
import ca.heretic.core.ModelEntry

class ModelCatalog(private val context: Context) {
    private val prefs = context.getSharedPreferences("catalog", Context.MODE_PRIVATE)
    val folder: Uri? get() = prefs.getString("folder", null)?.let(Uri::parse)

    fun remember(uri: Uri) {
        val old = folder
        grant(uri)
        check(prefs.edit().putString("folder", uri.toString()).commit()) { "Impossible de mémoriser le dossier." }
        if (old != null && old != uri) {
            val previous = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == old }
            val flags = (if (previous?.isReadPermission == true) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (previous?.isWritePermission == true) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            if (flags != 0) {
                try { context.contentResolver.releasePersistableUriPermission(old, flags) }
                catch (_: SecurityException) { /* The old grant might already have been revoked. */ }
            }
        }
    }
    fun grant(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch (_: SecurityException) { /* Read-only folders still support discovery. */ }
    }
    fun rename(model: DiscoveredModel, newName: String) {
        val name = newName.trim()
        require(name.isNotEmpty() && name.length <= 240 && name.none { it == '/' || it == '\\' || it == '\u0000' }) { "Nom de fichier invalide." }
        val ggufName = if (name.endsWith(".gguf", true)) name else "$name.gguf"
        check(DocumentsContract.renameDocument(context.contentResolver, uri(model), ggufName) != null) { "Ce fournisseur ne permet pas de renommer le fichier." }
    }
    fun delete(model: DiscoveredModel) {
        check(DocumentsContract.deleteDocument(context.contentResolver, uri(model))) { "Ce fournisseur ne permet pas de supprimer le fichier." }
    }
    fun move(model: DiscoveredModel, target: Uri) {
        grant(target)
        val source = folder ?: error("Choisissez un dossier.")
        require(source.authority == target.authority) { "Le déplacement direct exige le même fournisseur de fichiers. Utilisez une copie via le gestionnaire Android." }
        val parent = DocumentsContract.buildDocumentUriUsingTree(source, model.parentId)
        val destination = DocumentsContract.buildDocumentUriUsingTree(target, DocumentsContract.getTreeDocumentId(target))
        check(DocumentsContract.moveDocument(context.contentResolver, uri(model), parent, destination) != null) { "Ce fournisseur ne permet pas ce déplacement." }
    }
    fun uri(model: DiscoveredModel): Uri = DocumentsContract.buildDocumentUriUsingTree(
        folder ?: error("Choisissez un dossier de modèles."), model.id)

    fun scan(cancelled: () -> Boolean): List<DiscoveredModel> {
        val tree = folder ?: error("Choisissez d’abord un dossier contenant vos modèles GGUF.")
        try {
            val root = DocumentsContract.getTreeDocumentId(tree)
            return ModelDiscovery().scan(root, { id ->
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
                val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS)
                val entries = mutableListOf<ModelEntry>()
                var rows = 0
                context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        check(!cancelled()) { "Recherche arrêtée." }
                        check(++rows <= 10_000) { "Dossier trop volumineux. Choisissez un sous-dossier contenant vos modèles." }
                        // Virtual documents cannot be opened by the model import stream.
                        if (cursor.getInt(4) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0) continue
                        entries.add(ModelEntry(cursor.getString(0), cursor.getString(1) ?: "Sans nom",
                            if (cursor.isNull(2)) -1 else cursor.getLong(2),
                            cursor.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR))
                    }
                } ?: error("Impossible de lire ce dossier. Choisissez-le à nouveau.")
                entries
            }, cancelled)
        } catch (error: SecurityException) {
            throw IllegalStateException("Accès au dossier expiré ou refusé. Choisissez-le à nouveau.", error)
        }
    }
}
