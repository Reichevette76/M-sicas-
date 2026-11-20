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
        if (prefs.getString("jamendo_client_id", null).isNullOrBlank()) askClientId()
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
        val clientId = prefs.getString("jamendo_client_id", null)
        if (term.isEmpty()) { status.text = "Digite o nome de uma música ou artista."; return }
        if (clientId.isNullOrBlank()) { askClientId(); return }
        status.text = "Pesquisando no catálogo..."
        results.removeAllViews()
        executor.execute {
            try {
                val encoded = URLEncoder.encode(term, "UTF-8")
                val endpoint = "https://api.jamendo.com/v3.0/tracks/?client_id=" +
                    URLEncoder.encode(clientId, "UTF-8") + "&format=json&limit=25&search=" + encoded + "&audioformat=mp32"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val tracks = JSONObject(body).optJSONArray("results")
                runOnUiThread {
                    if (tracks == null || tracks.length() == 0) {
                        status.text = "Nenhuma música encontrada."
                        return@runOnUiThread
                    }
                    status.text = tracks.length().toString() + " resultado(s)."
                    for (i in 0 until tracks.length()) {
                        val item = tracks.getJSONObject(i)
                        val name = item.optString("name", "Faixa")
                        val artist = item.optString("artist_name", "Artista")
                        val allowed = item.optBoolean("audiodownload_allowed", false)
                        val downloadUrl = item.optString("audiodownload", "")
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(8, 12, 8, 12)
                        }
                        val label = TextView(this).apply {
                            text = "$name\n$artist" + if (allowed && downloadUrl.isNotBlank()) "\nDownload autorizado" else "\nSomente reprodução online"
                            setTextColor(0xFFFFFFFF.toInt())
                            textSize = 15f
                        }
                        row.addView(label)
                        if (allowed && downloadUrl.startsWith("https://")) {
                            val save = Button(this).apply {
                                text = "BAIXAR PARA OUVIR OFFLINE"
                                setOnClickListener { downloadTrack(downloadUrl, name, artist, this) }
                            }
                            row.addView(save)
                        }
                        results.addView(row)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "Erro na busca. Verifique a internet e o Client ID." }
            }
        }
    }

    private fun downloadTrack(url: String, name: String, artist: String, button: Button) {
        button.isEnabled = false
        button.text = "BAIXANDO..."
        executor.execute {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                val directory = File(filesDir, "music")
                if (!directory.exists()) directory.mkdirs()
                val safe = (artist + " - " + name).replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100)
                var file = File(directory, "$safe.mp3")
                var n = 2
                while (file.exists()) { file = File(directory, "$safe ($n).mp3"); n++ }
                connection.inputStream.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
                connection.disconnect()
                runOnUiThread {
                    button.text = "SALVA NA BIBLIOTECA"
                    button.isEnabled = true
                    status.text = "Música salva! Atualize a biblioteca para ouvir offline."
                    Toast.makeText(this, "Download concluído.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    button.text = "TENTAR NOVAMENTE"
                    button.isEnabled = true
                    status.text = "Não foi possível baixar esta faixa."
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
