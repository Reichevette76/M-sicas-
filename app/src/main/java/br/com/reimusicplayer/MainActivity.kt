package br.com.reimusicplayer

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import java.util.Locale
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
    private lateinit var voiceButton: Button
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceEnabled = false
    private var awaitingVoiceCommand = false
    private val voiceHandler = Handler(Looper.getMainLooper())
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

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) enableVoiceCommands()
            else Toast.makeText(this, "Permita o microfone para usar os comandos de voz.", Toast.LENGTH_LONG).show()
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
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(0xFF080808.toInt())
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(8))
        }
        val emblem = ImageView(this).apply {
            setImageResource(R.drawable.ic_rei_crown_headphones)
            contentDescription = "Coroa dourada com fones vermelhos"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        brand.addView(emblem, LinearLayout.LayoutParams(dp(82), dp(72)))
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rei = TextView(this).apply {
            text = "REI"
            textSize = 34f
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
        root.addView(brand, LinearLayout.LayoutParams(-1, dp(82)))

        // Botão de voz em posição prioritária, logo abaixo da identidade do aplicativo.
        voiceButton = makeButton("🎙  ATIVAR COMANDO DE VOZ")
        voiceButton.textSize = 13f
        voiceButton.minHeight = dp(50)
        voiceButton.minimumHeight = dp(50)
        voiceButton.setOnClickListener {
            if (voiceEnabled) {
                disableVoiceCommands()
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                enableVoiceCommands()
            } else {
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        root.addView(voiceButton, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(10) })

        status = TextView(this).apply {
            text = "PREPARANDO SUA BIBLIOTECA..."
            textSize = 11f
            setTextColor(0xFFFF858B.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF211014.toInt())
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), 0xFF6E252D.toInt())
            }
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(10) })

        val nowPlaying = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(17), dp(10), dp(17), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF18181B.toInt())
                cornerRadius = dp(19).toFloat()
                setStroke(dp(1), 0xFF38383E.toInt())
            }
        }
        val nowLabel = TextView(this).apply {
            text = "TOCANDO AGORA"
            textSize = 11f
            setTextColor(0xFFFF3945.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }
        nowPlaying.addView(nowLabel)
        currentTrack = TextView(this).apply {
            text = "Nenhuma música selecionada"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            maxLines = 2
            setPadding(0, dp(3), 0, 0)
        }
        nowPlaying.addView(currentTrack)
        root.addView(nowPlaying, LinearLayout.LayoutParams(-1, dp(78)).apply { bottomMargin = dp(10) })

        // Barra de transporte no estilo solicitado: aleatório, anterior, play, próxima e repetir.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        fun transportButton(symbol: String, primary: Boolean = false): TextView = TextView(this).apply {
            text = symbol
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = when {
                primary -> 31f
                symbol == "⤨" || symbol == "↻" -> 25f
                else -> 23f
            }
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFFF4F4F4.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (primary) 0xFFE01927.toInt() else android.graphics.Color.TRANSPARENT)
                if (primary) setStroke(dp(1), 0xFFFF5962.toInt())
            }
            elevation = if (primary) dp(5).toFloat() else 0f
            isClickable = true
            isFocusable = true
        }
        val shuffle = transportButton("⤨")
        val previous = transportButton("⏮")
        val playPause = transportButton("⊙")
        val next = transportButton("⏭")
        val repeat = transportButton("↻")
        shuffle.contentDescription = "Ativar ou desativar reprodução aleatória"
        previous.contentDescription = "Faixa anterior"
        playPause.contentDescription = "Reproduzir"
        next.contentDescription = "Próxima faixa"
        repeat.contentDescription = "Ativar ou desativar repetição"
        val sideParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        controls.addView(shuffle, LinearLayout.LayoutParams(0, dp(48), 0.8f))
        controls.addView(previous, LinearLayout.LayoutParams(0, dp(48), 0.9f))
        controls.addView(playPause, LinearLayout.LayoutParams(dp(68), dp(68)).apply {
            marginStart = dp(5); marginEnd = dp(5)
        })
        controls.addView(next, LinearLayout.LayoutParams(0, dp(48), 0.9f))
        controls.addView(repeat, LinearLayout.LayoutParams(0, dp(48), 0.8f))
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(76)).apply { bottomMargin = dp(9) })

        fun updateModeButton(button: TextView, enabled: Boolean) {
            button.setTextColor(if (enabled) 0xFFFFC94A.toInt() else 0xFFBFC0C5.toInt())
            button.alpha = if (enabled) 1f else 0.78f
        }
        shuffle.setOnClickListener {
            mediaController?.let { player ->
                player.shuffleModeEnabled = !player.shuffleModeEnabled
                updateModeButton(shuffle, player.shuffleModeEnabled)
            }
        }
        previous.setOnClickListener {
            mediaController?.let { player -> if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() }
        }
        playPause.setOnClickListener {
            mediaController?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    playPause.text = "⏵"
                    playPause.contentDescription = "Reproduzir"
                } else {
                    player.play()
                    playPause.text = "Ⅱ"
                    playPause.contentDescription = "Pausar"
                }
            }
        }
        next.setOnClickListener {
            mediaController?.let { player -> if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
        }
        repeat.setOnClickListener {
            mediaController?.let { player ->
                player.repeatMode = if (player.repeatMode == androidx.media3.common.Player.REPEAT_MODE_OFF)
                    androidx.media3.common.Player.REPEAT_MODE_ALL
                else androidx.media3.common.Player.REPEAT_MODE_OFF
                updateModeButton(repeat, player.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF)
            }
        }

        val libraryHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(5))
        }
        val libraryTitle = TextView(this).apply {
            text = "MINHA BIBLIOTECA"
            textSize = 14f
            setTextColor(0xFFF4F4F6.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        libraryHeader.addView(libraryTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val libraryHint = TextView(this).apply {
            text = "Toque para reproduzir"
            textSize = 11f
            setTextColor(0xFFA5A5AD.toInt())
        }
        libraryHeader.addView(libraryHint)
        root.addView(libraryHeader)

        trackList = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(0xFF303034.toInt())
            dividerHeight = dp(1)
            setBackgroundColor(0xFF111113.toInt())
            cacheColorHint = 0xFF111113.toInt()
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
        actions.addView(onlineButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        actions.addView(importButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(56)))

        val refresh = TextView(this).apply {
            text = "↻  ATUALIZAR BIBLIOTECA"
            textSize = 11f
            setTextColor(0xFFBDBDC2.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setOnClickListener { loadTracks() }
        }
        root.addView(refresh, LinearLayout.LayoutParams(-1, dp(28)))
        setContentView(root)
    }

    private fun enableVoiceCommands() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "O reconhecimento de voz não está disponível neste Android.", Toast.LENGTH_LONG).show()
            return
        }
        voiceEnabled = true
        awaitingVoiceCommand = false
        voiceButton.text = "■  DESATIVAR COMANDO DE VOZ"
        status.text = "COMANDO DE VOZ ATIVO • DIGA REI"
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (voiceEnabled) status.text = if (awaitingVoiceCommand) "OUVINDO COMANDO..." else "OUVINDO • DIGA REI"
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (voiceEnabled) restartVoiceListening(650L)
                }
                override fun onResults(results: Bundle?) {
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!phrase.isNullOrBlank()) handleVoicePhrase(phrase)
                    if (voiceEnabled) restartVoiceListening(350L)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        startVoiceListening()
    }

    private fun disableVoiceCommands() {
        voiceEnabled = false
        awaitingVoiceCommand = false
        voiceHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        if (::voiceButton.isInitialized) voiceButton.text = "🎙  ATIVAR COMANDO DE VOZ"
        if (::status.isInitialized) status.text = "COMANDO DE VOZ DESATIVADO"
    }

    private fun startVoiceListening() {
        if (!voiceEnabled) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            restartVoiceListening(1000L)
        }
    }

    private fun restartVoiceListening(delay: Long) {
        voiceHandler.removeCallbacksAndMessages(null)
        voiceHandler.postDelayed({
            if (voiceEnabled) startVoiceListening()
        }, delay)
    }

    private fun handleVoicePhrase(rawPhrase: String) {
        val phrase = rawPhrase.lowercase(Locale("pt", "BR"))
            .replace(Regex("[^a-záàâãéèêíïóôõöúç ]"), " ")
            .trim()
        val startsWithRei = phrase == "rei" || phrase.startsWith("rei ")
        val commandText = when {
            startsWithRei -> phrase.removePrefix("rei").trim()
            awaitingVoiceCommand -> phrase
            else -> return
        }

        if (startsWithRei && commandText.isBlank()) {
            awaitingVoiceCommand = true
            status.text = "REI ATIVADO • AGUARDANDO COMANDO"
            return
        }
        awaitingVoiceCommand = false
        val player = mediaController
        when {
            commandText.contains("desligar") || commandText.contains("desativa") || commandText.contains("parar de ouvir") -> {
                disableVoiceCommands()
                Toast.makeText(this, "Comando de voz desligado.", Toast.LENGTH_SHORT).show()
            }
            commandText.contains("pausar") || commandText == "pause" -> {
                player?.pause()
                status.text = "COMANDO RECONHECIDO • PAUSAR"
            }
            commandText.contains("próxima") || commandText.contains("proxima") || commandText.contains("avançar") || commandText.contains("avancar") -> {
                if (player?.hasNextMediaItem() == true) player.seekToNextMediaItem()
                status.text = "COMANDO RECONHECIDO • PRÓXIMA MÚSICA"
            }
            commandText.contains("anterior") || commandText.contains("voltar música") || commandText.contains("voltar musica") -> {
                if (player?.hasPreviousMediaItem() == true) player.seekToPreviousMediaItem()
                status.text = "COMANDO RECONHECIDO • MÚSICA ANTERIOR"
            }
            commandText.contains("aumentar volume") || commandText.contains("mais volume") || commandText.contains("volume acima") -> {
                if (player != null) player.volume = (player.volume + 0.1f).coerceAtMost(1f)
                status.text = "VOLUME AUMENTADO"
            }
            commandText.contains("diminuir volume") || commandText.contains("menos volume") || commandText.contains("volume abaixo") -> {
                if (player != null) player.volume = (player.volume - 0.1f).coerceAtLeast(0f)
                status.text = "VOLUME DIMINUÍDO"
            }
            commandText.contains("tocar") || commandText.contains("reproduzir") || commandText.contains("continuar") || commandText == "play" -> {
                player?.play()
                status.text = "COMANDO RECONHECIDO • TOCAR"
            }
            else -> {
                status.text = "NÃO ENTENDI • DIGA REI E O COMANDO"
            }
        }
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
                val item = tracks.getOrNull(position)
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    minimumHeight = dp(68)
                }
                row.removeAllViews()
                val cover = TextView(this@MainActivity).apply {
                    text = "♫"
                    textSize = 23f
                    gravity = Gravity.CENTER
                    setTextColor(0xFFFFC94A.toInt())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF292024.toInt())
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), 0xFF493039.toInt())
                    }
                }
                row.addView(cover, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
                val details = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
                val title = TextView(this@MainActivity).apply {
                    text = item?.mediaMetadata?.title?.toString() ?: "Faixa sem título"
                    setTextColor(0xFFF5F5F6.toInt())
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                }
                val artist = TextView(this@MainActivity).apply {
                    text = item?.mediaMetadata?.artist?.toString() ?: "Artista desconhecido"
                    setTextColor(0xFF9B9BA3.toInt())
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(3), 0, 0)
                }
                details.addView(title)
                details.addView(artist)
                row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                val play = TextView(this@MainActivity).apply {
                    text = "▶"
                    textSize = 16f
                    setTextColor(0xFFE52A35.toInt())
                    gravity = Gravity.CENTER
                }
                row.addView(play, LinearLayout.LayoutParams(dp(30), dp(48)))
                row.setBackgroundColor(if (position % 2 == 0) 0xFF151518.toInt() else 0xFF111113.toInt())
                return row
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
        disableVoiceCommands()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        controllerFuture = null
        super.onDestroy()
    }
}