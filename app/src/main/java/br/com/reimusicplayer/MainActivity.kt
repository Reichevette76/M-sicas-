package br.com.reimusicplayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()

        if (hasAudioPermission()) {
            connectToPlayer()
            loadTracks()
        } else {
            permissionRequest.launch(audioPermission())
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(12))
            setBackgroundColor(0xFF101827.toInt())
        }

        val title = TextView(this).apply {
            text = "REI MUSIC PLAYER"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(title, LinearLayout.LayoutParams(-1, dp(48)))

        val subtitle = TextView(this).apply {
            text = "Sua música • Offline • No seu Polo"
            textSize = 13f
            setTextColor(0xFF9FB0C7.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(subtitle, LinearLayout.LayoutParams(-1, dp(30)))

        status = TextView(this).apply {
            text = "Preparando sua biblioteca..."
            textSize = 14f
            setTextColor(0xFF42D6A4.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(42)))

        currentTrack = TextView(this).apply {
            text = "Nenhuma música tocando"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(currentTrack, LinearLayout.LayoutParams(-1, dp(54)))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val previous = makeButton("Anterior")
        val playPause = makeButton("Tocar / Pausar")
        val next = makeButton("Próxima")

        controls.addView(previous, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(5)
        })
        controls.addView(playPause, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(5)
            marginEnd = dp(5)
        })
        controls.addView(next, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(5)
        })
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(58)))

        previous.setOnClickListener { mediaController?.seekToPreviousMediaItem() }
        playPause.setOnClickListener {
            mediaController?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
        }
        next.setOnClickListener { mediaController?.seekToNextMediaItem() }

        val libraryTitle = TextView(this).apply {
            text = "MINHAS MÚSICAS"
            textSize = 12f
            setTextColor(0xFF42D6A4.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(libraryTitle)

        trackList = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(0x3342D6A4)
            dividerHeight = dp(1)
            setBackgroundColor(0xFF1A2638.toInt())
        }
        root.addView(trackList, LinearLayout.LayoutParams(-1, 0, 1f))

        trackList.setOnItemClickListener { _, _, position, _ ->
            val item = tracks.getOrNull(position) ?: return@setOnItemClickListener
            mediaController?.let { player ->
                player.setMediaItem(item)
                player.prepare()
                player.play()
                currentTrack.text = item.mediaMetadata.title?.toString() ?: "Tocando música"
            } ?: Toast.makeText(this, "Conectando ao player...", Toast.LENGTH_SHORT).show()
        }

        val refresh = makeButton("Atualizar biblioteca")
        refresh.setOnClickListener { loadTracks() }
        root.addView(refresh, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(10)
        })

        setContentView(root)
    }

    private fun makeButton(label: String): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            setTextColor(0xFF07131D.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF42D6A4.toInt())
        }
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
                        currentTrack.text = mediaMetadata.title?.toString() ?: "Rei Music Player"
                    }
                })
                status.text = "Player pronto para tocar offline."
            } catch (error: Exception) {
                status.text = "Não foi possível iniciar o player."
            }
        }, MoreExecutors.directExecutor())
    }

    private fun loadTracks() {
        if (!hasAudioPermission()) {
            permissionRequest.launch(audioPermission())
            return
        }

        tracks.clear()
        tracks.addAll(AudioLibrary.load(this))

        val names = tracks.map { item ->
            val song = item.mediaMetadata.title?.toString() ?: "Faixa sem título"
            val artist = item.mediaMetadata.artist?.toString() ?: "Artista desconhecido"
            "$song\n$artist"
        }

        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            names
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(0xFFFFFFFF.toInt())
                view.textSize = 14f
                view.setPadding(dp(14), dp(12), dp(10), dp(12))
                return view
            }
        }
        trackList.adapter = adapter
        status.text = if (tracks.isEmpty()) {
            "Nenhuma música encontrada. Coloque arquivos MP3 na pasta Música do celular."
        } else {
            "${tracks.size} música(s) encontrada(s)."
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
