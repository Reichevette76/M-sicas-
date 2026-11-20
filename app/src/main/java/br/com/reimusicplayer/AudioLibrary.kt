package br.com.reimusicplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File

object AudioLibrary {
    const val ROOT_ID = "rei_music_root"

    fun load(context: Context): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val collection = if (android.os.Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        val sortOrder = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"

        context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Faixa sem título"
                val artist = cursor.getString(artistColumn) ?: "Artista desconhecido"
                val contentUri = ContentUris.withAppendedId(collection, id)
                val metadata = MediaMetadata.Builder().setTitle(title).setArtist(artist)
                    .setIsBrowsable(false).setIsPlayable(true).build()
                result += MediaItem.Builder().setMediaId(contentUri.toString())
                    .setUri(contentUri).setMediaMetadata(metadata).build()
            }
        }

        val importedDirectory = File(context.filesDir, "music")
        importedDirectory.listFiles()?.filter { it.isFile && isAudioFile(it.name) }?.forEach { file ->
            val uri = Uri.fromFile(file)
            val metadata = MediaMetadata.Builder().setTitle(file.nameWithoutExtension)
                .setArtist("Adicionada ao Rei Music").setIsBrowsable(false).setIsPlayable(true).build()
            result.add(MediaItem.Builder().setMediaId(uri.toString()).setUri(uri).setMediaMetadata(metadata).build())
        }
        return result
    }

    fun importFiles(context: Context, uris: List<Uri>): Int {
        val directory = File(context.filesDir, "music")
        if (!directory.exists() && !directory.mkdirs()) return 0
        var imported = 0
        uris.forEach { uri ->
            try {
                val displayName = context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: ("musica_" + System.currentTimeMillis() + ".mp3")
                val safeName = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                if (!isAudioFile(safeName)) return@forEach
                val extension = safeName.substringAfterLast('.', "").lowercase()
                var target = File(directory, safeName)
                if (target.exists()) {
                    val base = safeName.substringBeforeLast('.', safeName)
                    val suffix = if (extension.isNotEmpty()) "." + extension else ""
                    var index = 2
                    while (target.exists()) {
                        target = File(directory, base + " (" + index + ")" + suffix)
                        index++
                    }
                }
                val input = context.contentResolver.openInputStream(uri) ?: return@forEach
                input.use { source -> target.outputStream().use { destination -> source.copyTo(destination) } }
                imported++
            } catch (_: Exception) {
                // Continue with the remaining selected files.
            }
        }
        return imported
    }

    fun isImported(item: MediaItem): Boolean = item.localConfiguration?.uri?.scheme == "file"

    fun deleteImported(context: Context, item: MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return false
        if (uri.scheme != "file") return false
        val directory = File(context.filesDir, "music").canonicalFile
        val file = File(uri.path ?: return false).canonicalFile
        if (file.parentFile != directory) return false
        return file.delete()
    }

    private fun isAudioFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus")
}