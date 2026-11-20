package br.com.reimusicplayer

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var librarySession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer

    private val libraryCallback = object : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {

            val root = MediaItem.Builder()
                .setMediaId(AudioLibrary.ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Minha biblioteca")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()

            return Futures.immediateFuture(
                LibraryResult.ofItem(root, params)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {

            val allTracks = if (parentId == AudioLibrary.ROOT_ID) {
                AudioLibrary.load(this@PlaybackService)
            } else {
                emptyList()
            }

            val start = (page * pageSize).coerceAtMost(allTracks.size)
            val end = (start + pageSize).coerceAtMost(allTracks.size)
            val pageItems = ImmutableList.copyOf(allTracks.subList(start, end))

            return Futures.immediateFuture(
                LibraryResult.ofItemList(pageItems, params)
            )
        }

        // Android Auto pode enviar apenas o mediaId ao selecionar uma faixa.
        // Recuperamos o MediaItem completo para que o ExoPlayer receba sua URI.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val availableItems = AudioLibrary.load(this@PlaybackService)
                .associateBy { it.mediaId }

            val resolvedItems = mutableListOf<MediaItem>()
            for (requested in mediaItems) {
                val requestedUri = requested.localConfiguration?.uri
                    ?: requested.requestMetadata.mediaUri

                val resolved = availableItems[requested.mediaId]
                    ?: requestedUri?.let { uri ->
                        availableItems[uri.toString()]
                            ?: MediaItem.Builder()
                                .setMediaId(requested.mediaId.ifBlank { uri.toString() })
                                .setUri(uri)
                                .setMediaMetadata(requested.mediaMetadata)
                                .build()
                    }
                    ?: requested.takeIf { it.localConfiguration != null }

                if (resolved == null) {
                    return Futures.immediateFailedFuture(
                        IllegalArgumentException(
                            "Música não encontrada na biblioteca: ${requested.mediaId}"
                        )
                    )
                }
                resolvedItems.add(resolved)
            }

            return Futures.immediateFuture(resolvedItems)
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        librarySession = MediaLibrarySession.Builder(this, player, libraryCallback).build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession? {
        return librarySession
    }

    override fun onDestroy() {
        librarySession?.release()
        if (::player.isInitialized) {
            player.release()
        }
        librarySession = null
        super.onDestroy()
    }
}
