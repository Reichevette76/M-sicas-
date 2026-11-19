package br.com.reimusicplayer

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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
        ): ListenableFuture<MediaLibraryService.LibraryResult<MediaItem>> {

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
                MediaLibraryService.LibraryResult.ofItem(root, params)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<MediaLibraryService.LibraryResult<ImmutableList<MediaItem>>> {

            val allTracks = if (parentId == AudioLibrary.ROOT_ID) {
                AudioLibrary.load(this@PlaybackService)
            } else {
                emptyList()
            }

            val start = (page * pageSize).coerceAtMost(allTracks.size)
            val end = (start + pageSize).coerceAtMost(allTracks.size)
            val pageItems = ImmutableList.copyOf(allTracks.subList(start, end))

            return Futures.immediateFuture(
                MediaLibraryService.LibraryResult.ofItemList(pageItems, params)
            )
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
