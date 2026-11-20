package br.com.reimusicplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class OnlineSearchActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var query: EditText
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("online_music", MODE_PRIVATE) }
    private val defaultJamendoClientId = "b4338639"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(0xFF080808.toInt())
        }
        val title = TextView(this).apply {
            text = "BUSCAR MÚSICAS ONLINE"
            textSize = 20f
            setTextColor(0xFFE31B23.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(title)
        query = EditText(this).apply {
            hint = "Digite música ou artista"
            setSingleLine(true)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFAAAAAA.toInt())
        }
        root.addView(query)
        val youtube = Button(this).apply {
            text = "PESQUISAR NO YOUTUBE"
            setOnClickListener {
                val term = query.text.toString().trim()
                if (term.isNotEmpty()) {
                    val url = "https://www.youtube.com/results?search_query=" + Uri.encode(term)
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        }
        root.addView(youtube)
        val jamendo = Button(this).apply {
            text = "BUSCAR NO CATÁLOGO AUTORIZADO"
            setOnClickListener { searchJamendo() }
        }
        root.addView(jamendo)
        val keyButton = Button(this).apply {
            text = "CONFIGURAR ACESSO AO CATÁLOGO"
            setOnClickListener { askClientId() }
        }
        root.addView(keyButton)
        status = TextView(this).apply {
            text = "YouTube abre no serviço oficial. No catálogo, baixe apenas faixas autorizadas."
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 12, 0, 12)
        }
        root.addView(status)
        val scroll = ScrollView(this)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(results)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (prefs.getString("jamendo_client_id", null).isNullOrBlank()) {
            prefs.edit().putString("jamendo_client_id", defaultJamendoClientId).apply()
            status.text = "Catálogo autorizado conectado. Digite uma música ou artista para pesquisar."
        }
    }

    private fun askClientId() {
        val input = EditText(this).apply {
            hint = "Cole aqui seu Client ID do Jamendo"
            setSingleLine(true)
            setText(prefs.getString("jamendo_client_id", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("Conectar catálogo autorizado")
            .setMessage("Crie uma aplicação gratuita no portal de desenvolvedores Jamendo e cole o Client ID aqui.")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val id = input.text.toString().trim()
                if (id.isNotEmpty()) {
                    prefs.edit().putString("jamendo_client_id", id).apply()
                    status.text = "Acesso ao catálogo configurado."
                }
            }
            .setNegativeButton("Depois", null)
            .setNeutralButton("Abrir cadastro") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://devportal.jamendo.com/")))
            }
            .show()
    }

    private fun searchJamendo() {
        val term = query.text.toString().trim()
        val clientId = prefs.getString("jamendo_client_id", defaultJamendoClientId)
        if (term.isEmpty()) { status.text = "Digite o nome de uma música ou artista."; return }
        if (clientId.isNullOrBlank()) { askClientId(); return }
        status.text = "Pesquisando no catálogo..."
        results.removeAllViews()
        executor.execute {
            try {
                val encoded = URLEncoder.encode(term, "UTF-8")
                val endpoint = "https://api.jamendo.com/v3.0/tracks/?client_id=" +
                    URLEncoder.encode(clientId, "UTF-8") + "&format=json&limit=25&search=" + encoded +
                    "&audioformat=mp32&audiodlformat=mp32"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                val body = try {
                    val code = connection.responseCode
                    if (code !in 200..299) throw IllegalStateException("Jamendo HTTP $code")
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
                val response = JSONObject(body)
                val tracks = response.optJSONArray("results")
                runOnUiThread {
                    if (tracks == null || tracks.length() == 0) {
                        status.text = "Nenhuma música encontrada."
                        return@runOnUiThread
                    }
                    status.text = tracks.length().toString() + " resultado(s)."
                    for (i in 0 until tracks.length()) {
                        val item = tracks.getJSONObject(i)
                        val id = item.optString("id", "")
                        val name = item.optString("name", "Faixa")
                        val artist = item.optString("artist_name", "Artista")
                        val allowed = item.optBoolean("audiodownload_allowed", false)
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(8, 12, 8, 12)
                        }
                        val label = TextView(this).apply {
                            text = "$name\n$artist" + if (allowed && id.isNotBlank()) "\nDownload autorizado" else "\nSomente reprodução online"
                            setTextColor(0xFFFFFFFF.toInt())
                            textSize = 15f
                        }
                        row.addView(label)
                        if (allowed && id.isNotBlank()) {
                            val save = Button(this).apply {
                                text = "BAIXAR PARA OUVIR OFFLINE"
                                setOnClickListener { downloadTrack(id, clientId, name, artist, this) }
                            }
                            row.addView(save)
                        }
                        results.addView(row)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = "Erro na busca: " + (error.message ?: "verifique a internet e o Client ID.")
                }
            }
        }
    }

    // O método oficial /tracks/file exige client_id + id e responde com redirecionamento
    // para o arquivo. Usamos esse endpoint em vez de depender de um link de download antigo.
    private fun downloadTrack(trackId: String, clientId: String, name: String, artist: String, button: Button) {
        button.isEnabled = false
        button.text = "BAIXANDO..."
        status.text = "Baixando música autorizada..."
        executor.execute {
            var temporary: File? = null
            var finalFile: File? = null
            try {
                val endpoint = "https://api.jamendo.com/v3.0/tracks/file/?" +
                    "client_id=" + URLEncoder.encode(clientId, "UTF-8") +
                    "&id=" + URLEncoder.encode(trackId, "UTF-8") +
                    "&audioformat=mp32&action=download"
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 90000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "ReiMusicPlayer/0.5.3 (Android)")
                    setRequestProperty("Accept", "audio/mpeg, audio/*, application/octet-stream, */*")
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                            ?.take(180).orEmpty()
                        throw IllegalStateException("Jamendo respondeu HTTP $code" +
                            if (detail.isNotBlank()) ": $detail" else "")
                    }
                    val mime = connection.contentType?.lowercase().orEmpty()
                    if (mime.contains("text/html") || mime.contains("application/json") || mime.contains("text/plain")) {
                        val detail = connection.inputStream.bufferedReader().use { it.readText() }.take(180)
                        throw IllegalStateException("O Jamendo não entregou o áudio: $detail")
                    }

                    val directory = File(filesDir, "music")
                    if (!directory.exists() && !directory.mkdirs()) {
                        throw IllegalStateException("Não foi possível criar a biblioteca.")
                    }
                    val safe = (artist + " - " + name)
                        .replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(100)
                    var destination = File(directory, "$safe.mp3")
                    var number = 2
                    while (destination.exists()) {
                        destination = File(directory, "$safe ($number).mp3")
                        number++
                    }
                    finalFile = destination
                    temporary = File(directory, destination.name + ".part")
                    connection.inputStream.use { source ->
                        temporary!!.outputStream().buffered().use { target ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                target.write(buffer, 0, count)
                            }
                        }
                    }
                    if (!temporary!!.exists() || temporary!!.length() < 1024L) {
                        throw IllegalStateException("O arquivo recebido está vazio ou incompleto.")
                    }
                    if (!temporary!!.renameTo(destination)) {
                        temporary!!.copyTo(destination, overwrite = false)
                        temporary!!.delete()
                    }
                } finally {
                    connection.disconnect()
                }

                runOnUiThread {
                    button.text = "BAIXADA • NA BIBLIOTECA"
                    button.isEnabled = false
                    status.text = "Download concluído: " + (finalFile?.name ?: "música") +
                        ". Atualize a biblioteca para ouvir offline."
                    Toast.makeText(this, "Música baixada para a biblioteca do Rei Music.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                temporary?.delete()
                finalFile?.takeIf { it.exists() && it.length() == 0L }?.delete()
                runOnUiThread {
                    button.text = "TENTAR NOVAMENTE"
                    button.isEnabled = true
                    status.text = "Falha no download: " + (error.message ?: "erro desconhecido")
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
