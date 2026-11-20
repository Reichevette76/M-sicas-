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
            setPadding(dp(18), dp(14), dp(18), dp(12))
            setBackgroundColor(0xFF080808.toInt())
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        }
        val crown = ImageView(this).apply {
            setImageResource(R.drawable.ic_rei_crown_headphones)
            contentDescription = "Coroa com fones de ouvido"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        brand.addView(crown, LinearLayout.LayoutParams(dp(68), dp(58)))
        val rei = TextView(this).apply {
            text = "REI"
            textSize = 34f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
        }
        brand.addView(rei)
        val music = TextView(this).apply {
            text = "M U S I C"
            textSize = 13f
            setTextColor(0xFFE31B23.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.18f
        }
        brand.addView(music)
        root.addView(brand, LinearLayout.LayoutParams(-1, dp(142)))

        val subtitle = TextView(this).apply {
            text = "SUA MÚSICA. DO SEU JEITO."
            textSize = 11f
            setTextColor(0xFFB7B7B7.toInt())
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }
        root.addView(subtitle, LinearLayout.LayoutParams(-1, dp(28)))

        status = TextView(this).apply {
            text = "Preparando sua biblioteca..."
            textSize = 13f
            setTextColor(0xFFE31B23.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(38)))

        currentTrack = TextView(this).apply {
            text = "NENHUMA MÚSICA TOCANDO"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFF191919.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(currentTrack, LinearLayout.LayoutParams(-1, dp(58)))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        val previous = makeButton("ANTERIOR")
        val playPause = makeButton("TOCAR / PAUSAR")
        val next = makeButton("PRÓXIMA")
        controls.addView(previous, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        controls.addView(playPause, LinearLayout.LayoutParams(0, dp(48), 1.25f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
        controls.addView(next, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(64)))

        previous.setOnClickListener {
            mediaController?.let { player -> if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() }
        }
        playPause.setOnClickListener {
            mediaController?.let { player -> if (player.isPlaying) player.pause() else player.play() }
        }
        next.setOnClickListener {
            mediaController?.let { player -> if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
        }

        val libraryTitle = TextView(this).apply {
            text = "MINHAS MÚSICAS"
            textSize = 13f
            setTextColor(0xFFE31B23.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(dp(2), dp(10), 0, dp(8))
        }
        root.addView(libraryTitle)

        trackList = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(0xFF333333.toInt())
            dividerHeight = dp(1)
            setBackgroundColor(0xFF121212.toInt())
            cacheColorHint = 0xFF121212.toInt()
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

        val onlineButton = makeButton("BUSCAR MÚSICAS ONLINE")
        onlineButton.setOnClickListener { startActivity(android.content.Intent(this, OnlineSearchActivity::class.java)) }
        root.addView(onlineButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })

        val importButton = makeButton("ADICIONAR MÚSICAS")
        importButton.setOnClickListener { audioPicker.launch(arrayOf("audio/*")) }
        root.addView(importButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })

        val refresh = makeButton("ATUALIZAR BIBLIOTECA")
        refresh.setOnClickListener { loadTracks() }
        root.addView(refresh, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })
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
            textSize = 11f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFD71920.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            minHeight = dp(44)
            minimumHeight = dp(44)
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