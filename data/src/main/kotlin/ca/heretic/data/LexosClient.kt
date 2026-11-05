package ca.heretic.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** No network request is made by the local inference path. Token stays in memory. */
class LexosClient(private val context: Context) {
    private val prefs = context.getSharedPreferences("lexos-lab", Context.MODE_PRIVATE)
    var endpoint: String
        get() = prefs.getString("endpoint", "") ?: ""
        set(value) { val u = URI(value.trim()); require(u.scheme == "https" && u.host != null && u.userInfo == null && u.query == null && u.fragment == null) { "Une URL HTTPS sans identifiants est requise." }; check(prefs.edit().putString("endpoint", value.trim().trimEnd('/')).commit()) }
    var token: String = ""
    val lastJob: String get() = prefs.getString("job", "") ?: ""
    fun connect(value: String, secret: String): JSONObject { endpoint = value; token = secret.trim(); return request("GET", "/capabilities") }
    private fun connection(method: String, path: String): HttpURLConnection {
        require(token.isNotBlank()) { "Saisissez le jeton LexOS pour cette session." }
        return (URL(endpoint + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
        }
    }
    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val c = connection(method, path)
        try {
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
            require(c.responseCode in 200..299) { "LexOS : HTTP ${c.responseCode}. Vérifiez le jeton, la tâche et les recettes configurées sur le PC." }
            val text = c.inputStream.bufferedReader().use { it.readText().also { value -> require(value.length <= 1_000_000) } }
            return JSONObject(text)
        } finally { c.disconnect() }
    }
    fun submit(operation: String, model: String): JSONObject {
        val result = request("POST", "/jobs", JSONObject().put("operation", operation).put("model", model))
        check(prefs.edit().putString("job", result.getString("id")).commit()); return result
    }
    private fun job(): String { require(lastJob.matches(Regex("[a-f0-9]{32}"))) { "Aucune tâche mémorisée." }; return lastJob }
    fun progress() = request("GET", "/jobs/${job()}")
    fun download(destination: Uri) {
        val c = connection("GET", "/jobs/${job()}/artifact")
        try {
            require(c.responseCode == 200) { "Résultat indisponible : HTTP ${c.responseCode}." }
            context.contentResolver.openOutputStream(destination, "wt")?.use { out -> c.inputStream.use { it.copyTo(out) } } ?: error("Destination inaccessible.")
        } finally { c.disconnect() }
    }
}
