override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        librarySession = MediaLibrarySession.Builder(this, player, libraryCallback).build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession? {
        return librarySession
    }

    override fun onDestroy() {
        librarySession?.run {
            player.release()
            release()
        }
        librarySession = null
        super.onDestroy()
    }
}
