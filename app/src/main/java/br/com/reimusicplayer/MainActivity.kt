package br.com.reimusicplayer

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var currentTrack: TextView
    private lateinit var trackList: ListView
    private val tracks = mutableListOf<MediaItem>()
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                connectToPlayer()
                loadTracks()
            } else {
                status.text = "Permita o acesso aos arquivos de áudio para listar suas músicas."
            }
        }

    private val audioPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val imported = AudioLibrary.importFiles(this, uris)
                Toast.makeText(this, imported.toString() + " música(s) adicionada(s).", Toast.LENGTH_LONG).show()
                loadTracks()
            }
        }

    private val deleteRequest =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Toast.makeText(this, "Música excluída.", Toast.LENGTH_SHORT).show()
                loadTracks()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF080808.toInt()
        window.navigationBarColor = 0xFF080808.toInt()
        buildScreen()
        if (hasAudioPermission()) {
            connectToPlayer()
            loadTracks()
        } else {
            permissionRequest.launch(audioPermission())
        }
    }

    override fun onResume() {
        super.onResume()
        if (::trackList.isInitialized && hasAudioPermission()) loadTracks()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(0xFF080808.toInt())
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        val emblem = ImageView(this).apply {
            setImageResource(R.drawable.ic_rei_crown_headphones)
            contentDescription = "Coroa dourada com fones vermelhos"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        brand.addView(emblem, LinearLayout.LayoutParams(dp(74), dp(68)))
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rei = TextView(this).apply {
            text = "REI"
            textSize = 30f
            setTextColor(0xFFE6E8ED.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            letterSpacing = 0.10f
        }
        brandText.addView(rei)
        val music = TextView(this).apply {
            text = "M U S I C   P L A Y E R"
            textSize = 11f
            setTextColor(0xFFE31B23.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.10f
        }
        brandText.addView(music)
        brand.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(brand, LinearLayout.LayoutParams(-1, dp(76)))

        status = TextView(this).apply {
            text = "Preparando sua biblioteca..."
            textSize = 12f
            setTextColor(0xFFFF7777.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF241012.toInt())
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), 0xFF632026.toInt())
            }
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(8) })

        val nowPlaying = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF171719.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0xFF333338.toInt())
            }
        }
        val nowLabel = TextView(this).apply {
            text = "TOCANDO AGORA"
            textSize = 10f
            setTextColor(0xFFE31B23.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }
        nowPlaying.addView(nowLabel)
        currentTrack = TextView(this).apply {
            text = "Nenhuma música selecionada"
            textSize = 14f
            setTextColor(0xFFF5F5F5.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            maxLines = 2
            setPadding(0, dp(3), 0, 0)
        }
        nowPlaying.addView(currentTrack)
        root.addView(nowPlaying, LinearLayout.LayoutParams(-1, dp(66)).apply { bottomMargin = dp(8) })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val previous = makeButton("‹  ANTERIOR")
        val playPause = makeButton("▶  TOCAR / PAUSAR")
        val next = makeButton("PRÓXIMA  ›")
        controls.addView(previous, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(4) })
        controls.addView(playPause, LinearLayout.LayoutParams(0, dp(46), 1.35f).apply { marginStart = dp(4); marginEnd = dp(4) })
        controls.addView(next, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(4) })
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(7) })

        previous.setOnClickListener {
            mediaController?.let { player -> if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() }
        }
        playPause.setOnClickListener {
            mediaController?.let { player -> if (player.isPlaying) player.pause() else player.play() }
        }
        next.setOnClickListener {
            mediaController?.let { player -> if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
        }

        val libraryHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(5))
        }
        val libraryTitle = TextView(this).apply {
            text = "MINHA BIBLIOTECA"
            textSize = 13f
            setTextColor(0xFFF1F1F1.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        libraryHeader.addView(libraryTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val libraryHint = TextView(this).apply {
            text = "Toque para reproduzir"
            textSize = 10f
            setTextColor(0xFF929292.toInt())
        }
        libraryHeader.addView(libraryHint)
        root.addView(libraryHeader)

        trackList = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(0xFF303034.toInt())
            dividerHeight = dp(1)
            setBackgroundColor(0xFF141416.toInt())
            cacheColorHint = 0xFF141416.toInt()
            isVerticalScrollBarEnabled = true
        }
        root.addView(trackList, LinearLayout.LayoutParams(-1, 0, 1f))
        trackList.setOnItemClickListener { _, _, position, _ ->
            if (tracks.isEmpty()) return@setOnItemClickListener
            mediaController?.let { player ->
                player.setMediaItems(tracks, position, 0L)
                player.prepare()
                player.play()
                currentTrack.text = tracks[position].mediaMetadata.title?.toString() ?: "Tocando música"
            } ?: Toast.makeText(this, "Conectando ao player...", Toast.LENGTH_SHORT).show()
        }
        trackList.setOnItemLongClickListener { _, _, position, _ ->
            confirmDeleteTrack(position)
            true
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val onlineButton = makeButton("BUSCAR ONLINE")
        onlineButton.setOnClickListener { startActivity(android.content.Intent(this, OnlineSearchActivity::class.java)) }
        val importButton = makeButton("＋  IMPORTAR")
        importButton.setOnClickListener { audioPicker.launch(arrayOf("audio/*")) }
        actions.addView(onlineButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        actions.addView(importButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) })
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(52)))

        val refresh = TextView(this).apply {
            text = "↻  ATUALIZAR BIBLIOTECA"
            textSize = 10f
            setTextColor(0xFFBDBDC2.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setOnClickListener { loadTracks() }
        }
        root.addView(refresh, LinearLayout.LayoutParams(-1, dp(28)))
        setContentView(root)
    }

    private fun confirmDeleteTrack(position: Int) {
        val item = tracks.getOrNull(position) ?: return
        val title = item.mediaMetadata.title?.toString() ?: "esta música"
        AlertDialog.Builder(this)
            .setTitle("Remover música")
            .setMessage("Deseja excluir \"" + title + "\"? Para arquivos do celular, o Android pedirá confirmação.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Excluir") { _, _ -> deleteTrack(item) }
            .show()
    }

    private fun deleteTrack(item: MediaItem) {
        if (AudioLibrary.isImported(item)) {
            if (AudioLibrary.deleteImported(this, item)) {
                Toast.makeText(this, "Música removida da biblioteca.", Toast.LENGTH_SHORT).show()
                loadTracks()
            } else {
                Toast.makeText(this, "Não foi possível remover a música.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val uri = item.localConfiguration?.uri ?: return
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val request = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                deleteRequest.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } else if (Build.VERSION.SDK_INT == 29) {
                try {
                    contentResolver.delete(uri, null, null)
                    loadTracks()
                } catch (error: RecoverableSecurityException) {
                    val request = IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build()
                    deleteRequest.launch(request)
                }
            } else {
                Toast.makeText(this, "Use o gerenciador de arquivos para excluir este arquivo.", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "O Android não permitiu excluir este arquivo.", Toast.LENGTH_LONG).show()
        }
    }

    private fun makeButton(label: String): Button =
        Button(this).apply {
            text = label
            textSize = 10f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = null
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFD71920.toInt())
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), 0xFFFF4B52.toInt())
            }
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            minHeight = dp(42)
            minimumHeight = dp(42)
            setPadding(dp(5), 0, dp(5), 0)
            stateListAnimator = null
            elevation = dp(2).toFloat()
        }

    private fun connectToPlayer() {
        if (controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                mediaController?.addListener(object : Player.Listener {
                    override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                        currentTrack.text = mediaMetadata.title?.toString() ?: "REI MUSIC PLAYER"
                    }
                })
                status.text = "PLAYER PRONTO • MODO OFFLINE"
            } catch (_: Exception) {
                status.text = "NÃO FOI POSSÍVEL INICIAR O PLAYER"
            }
        }, MoreExecutors.directExecutor())
    }

    private fun loadTracks() {
        // Downloads salvos na pasta privada do app devem aparecer mesmo sem permissão
        // para ler a biblioteca de áudio externa do Android.
        tracks.clear()
        tracks.addAll(AudioLibrary.load(this))
        val names = tracks.map { item ->
            val song = item.mediaMetadata.title?.toString() ?: "Faixa sem título"
            val artist = item.mediaMetadata.artist?.toString() ?: "Artista desconhecido"
            song + "\n" + artist
        }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(0xFFFFFFFF.toInt())
                view.textSize = 14f
                view.setPadding(dp(14), dp(12), dp(10), dp(12))
                view.setBackgroundColor(0xFF121212.toInt())
                return view
            }
        }
        trackList.adapter = adapter
        status.text = if (tracks.isEmpty()) {
            "NENHUMA MÚSICA ENCONTRADA • ADICIONE ARQUIVOS"
        } else {
            tracks.size.toString() + " MÚSICA(S) NA BIBLIOTECA"
        }
    }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        controllerFuture = null
        super.onDestroy()
    }
}