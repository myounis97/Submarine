package com.myounis.submarine

import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.text.TextUtils
import android.webkit.MimeTypeMap
import androidx.annotation.Nullable
import androidx.exifinterface.media.ExifInterface
import app.cash.copper.rx3.mapToList
import app.cash.copper.rx3.mapToOne
import app.cash.copper.rx3.observeQuery
import io.reactivex.rxjava3.annotations.NonNull
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import java.io.IOException


class Submarine private constructor(private val context: Context) {

    companion object {
        const val DEFAULT_PAGE_SIZE = 15
    }

    enum class GalleryType {
        IMAGES, VIDEOS, IMAGES_VIDEOS, AUDIO
    }

    private var pageSize: Int = DEFAULT_PAGE_SIZE

    /*------------------------- Albums ----------------------------------------*/

    fun loadAlbums(galleryType: GalleryType): @NonNull Flowable<List<Album>> {

        val projection = arrayOf(
                MediaStore.Files.FileColumns.BUCKET_ID,
                MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME
        )

        val selection = when (galleryType) {

            GalleryType.IMAGES -> MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE

            GalleryType.VIDEOS -> MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

            GalleryType.IMAGES_VIDEOS -> (MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                    + " OR "
                    + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)

            GalleryType.AUDIO -> MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO

        }

        val uri = MediaStore.Files.getContentUri("external")

        return context.contentResolver
                .observeQuery(uri, projection, selection,
                        null, null)
                .mapToList {
                    fetchAlbumFromCursor(it)
                }
                .toFlowable(BackpressureStrategy.LATEST)

    }

    private fun fetchAlbumFromCursor(cursor: Cursor): Album {

        val id = cursor.getLong(cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_ID))

        val name = cursor.getString(cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME))

        return Album(id, name)

    }

    /*------------------------- Images and Videos -----------------------------*/

    fun loadMedia(ids: List<Long>): @NonNull Flowable<List<BaseMedia>> {

        val projection = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.WIDTH,
                MediaStore.Files.FileColumns.HEIGHT,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        val uri = MediaStore.Files.getContentUri("external")

        val selection = "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE +
                " OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")" +
                " AND " + MediaStore.Files.FileColumns._ID + " IN ( " + TextUtils.join(",", ids) + " )";

        return context.contentResolver
                .observeQuery(uri, projection, selection,
                        null, null)
                .mapToList {
                    fetchMediaFromCursor(it)
                }
                .toFlowable(BackpressureStrategy.LATEST)

    }

    fun loadMedia(id: Long): @NonNull Flowable<BaseMedia> {

        val projection = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.WIDTH,
                MediaStore.Files.FileColumns.HEIGHT,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        val uri = MediaStore.Files.getContentUri("external")

        val selection = "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE +
                " OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")" +
                " AND " + MediaStore.Files.FileColumns._ID + "=" + "'$id'"

        return context.contentResolver
                .observeQuery(uri, projection, selection,
                        null, null)
                .mapToOne {
                    fetchMediaFromCursor(it)
                }
                .toFlowable(BackpressureStrategy.LATEST)

    }

    fun loadImagesAndVideos(pageNumber: Int, @Nullable album: Album? = null): @NonNull Flowable<List<BaseMedia>> {

        val projection = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.WIDTH,
                MediaStore.Files.FileColumns.HEIGHT,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        val offset = pageNumber * pageSize

        val uri = MediaStore.Files.getContentUri("external").buildUpon()
                .encodedQuery("limit=$offset,$pageSize")
                .build()

        var selection = "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE +
                " OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")"

        if (album != null) {
            selection += " AND " + MediaStore.Files.FileColumns.BUCKET_ID + "=" + album.id
        }

        return context.contentResolver
                .observeQuery(uri, projection, selection,
                        null, MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")
                .mapToList {
                    fetchMediaFromCursor(it)
                }
                .toFlowable(BackpressureStrategy.LATEST)

    }

    private fun fetchMediaFromCursor(cursor: Cursor): BaseMedia {

        val mimeType = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE))

        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)

        if (isVideo(mimeType)) {

            val video = Video()

            video.path = cursor.getString(cursor.getColumnIndex(MediaStore.Video.Media.DATA))

            video.extension = extension

            video.name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME))

            video.mimeType = cursor.getString(cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE))

            video.size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE))

            var width = cursor.getInt(cursor.getColumnIndex(MediaStore.Video.Media.WIDTH))

            video.width = width

            var height = cursor.getInt(cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT))

            video.height = height

            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))

            video.id = id

            val contentUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())

            video.contentUri = contentUri

            try {

                context.contentResolver.openFileDescriptor(contentUri, "r").use { pfd ->

                    val mediaMetadataRetriever = MediaMetadataRetriever()

                    if (pfd != null) {

                        mediaMetadataRetriever.setDataSource(pfd.fileDescriptor)

                        if (width == 0 || height == 0) {

                            width = Integer.valueOf(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))

                            height = Integer.valueOf(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))

                            video.width = width

                            video.height = height

                        }

                        video.rotation = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()

                        when (video.rotation) {
                            90, 270 -> {
                                video.width = height
                                video.height = width
                            }
                        }

                        video.duration = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION).toLong()

                        video.album = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)

                        video.artist = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)

                    }

                    mediaMetadataRetriever.release()

                }

            } catch (e: IOException) {
                e.printStackTrace()
            }

            try {
                video.dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED))
            } catch (ex: java.lang.Exception) {
                ex.printStackTrace()
                video.dateAdded = 0
            }

            try {
                video.dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED))
            } catch (ex: java.lang.Exception) {
                ex.printStackTrace()
                video.dateModified = 0
            }

            checkWidthAndHeight(video)

            return video

        } else {

            val image = Image()

            image.isGif = isGif(mimeType)

            image.extension = extension

            image.path = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DATA))

            image.name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))

            image.mimeType = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE))

            image.size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))

            val width = cursor.getInt(cursor.getColumnIndex(MediaStore.Images.Media.WIDTH))

            image.width = width

            val height = cursor.getInt(cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT))

            image.height = height

            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))

            image.id = (id)

            val contentUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
            image.contentUri = contentUri

            try {

                context.contentResolver.openFileDescriptor(contentUri, "r").use { pfd ->

                    if (pfd != null) {

                        val exifInterface: ExifInterface = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                            ExifInterface(pfd.fileDescriptor)
                        } else {
                            ExifInterface(image.path!!)
                        }

                        when (exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)) {

                            ExifInterface.ORIENTATION_ROTATE_180,
                            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                                image.rotation = 180
                            }

                            ExifInterface.ORIENTATION_TRANSVERSE,
                            ExifInterface.ORIENTATION_ROTATE_90 -> {

                                image.rotation = 90

                                image.height = width
                                image.width = height
                            }

                            ExifInterface.ORIENTATION_TRANSPOSE,
                            ExifInterface.ORIENTATION_ROTATE_270 -> {

                                image.rotation = 270

                                image.height = width
                                image.width = height
                            }

                            else -> image.rotation = 0
                        }

                    }

                }

            } catch (e: IOException) {
                e.printStackTrace()
            }

            try {
                image.dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
            } catch (ex: Exception) {
                ex.printStackTrace()
                image.dateAdded = 0
            }

            try {
                image.dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED))
            } catch (ex: Exception) {
                ex.printStackTrace()
                image.dateModified = 0
            }

            checkWidthAndHeight(image)

            return image

        }

    }

    private fun checkWidthAndHeight(media: BaseMedia) {

        if (media.width == 0 || media.height == 0)
            throw IllegalStateException("Width and Height must not be zeros")

    }

    /*------------------------- Audios -----------------------------*/

    fun loadAudio(id: Long): @NonNull Flowable<Audio> {

        val projection = arrayOf(
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.AudioColumns.SIZE,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.ARTIST_ID)

        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val selection = MediaStore.Audio.Media._ID + "=" + id

        return context.contentResolver
                .observeQuery(uri, projection, selection,
                        null, null)
                .mapToOne {
                    fetchAudioFromCursor(it)
                }
                .toFlowable(BackpressureStrategy.LATEST)

    }

    fun loadAudios(pageNumber: Int): @NonNull Flowable<List<Audio>> {

        val projection = arrayOf(
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.AudioColumns.SIZE,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.ARTIST_ID)

        val offset = pageNumber * pageSize

        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .encodedQuery("limit=$offset,$pageSize")
                .build()

        return context.contentResolver
                .observeQuery(uri, projection, null,
                        null, MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")
                .mapToList {
                    fetchAudioFromCursor(it)
                }
                .toFlowable(BackpressureStrategy.LATEST)

    }

    private fun fetchAudioFromCursor(cursor: Cursor): Audio {

        val audio = Audio()

        audio.path = cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.DATA))

        val songName = cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME))

        audio.name = songName

        audio.mimeType = cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE))

        val songTitle = cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.TITLE))
        audio.title = songTitle

        val id = cursor.getLong(cursor.getColumnIndex(MediaStore.Audio.Media._ID))
        audio.id = id

        val contentUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
        audio.contentUri = contentUri

        try {
            audio.dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED))
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            audio.dateAdded = 0
        }

        try {
            audio.dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED))
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            audio.dateModified = 0
        }

        try {

            context.contentResolver.openFileDescriptor(contentUri, "r").use { pfd ->

                val mediaMetadataRetriever = MediaMetadataRetriever()

                if (pfd != null) {

                    mediaMetadataRetriever.setDataSource(pfd.fileDescriptor)

                    audio.duration = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION).toLong()

                    audio.bitRate = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE).toInt()

                }

                mediaMetadataRetriever.release()

            }

        } catch (e: IOException) {
            e.printStackTrace()
        }

        audio.size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))

        val albumName = cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM))
        audio.album = albumName

        val artistName = cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST))
        audio.artist = artistName

        val albumId = cursor.getLong(cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID))

        val sArtworkUri = Uri.parse("content://media/external/audio/albumart")

        val imageUri = Uri.withAppendedPath(sArtworkUri, albumId.toString())

        audio.imageUri = imageUri

        return audio

    }

    /*------------------------- Videos -----------------------------*/

    fun loadVideo(id: Long): Flowable<Video> {

        val projection = arrayOf(
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.ALBUM,
                MediaStore.Video.Media.ARTIST)

        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val selection = MediaStore.Video.Media._ID + "=" + id

        return context.contentResolver
                .observeQuery(uri, projection, selection,
                        null, null)
                .mapToOne {
                    fetchVideoFromCursor(it)
                }
                .toFlowable(BackpressureStrategy.LATEST)

    }

    fun loadVideos(pageNumber: Int): @NonNull Flowable<List<Video>> {

        val projection = arrayOf(
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.ALBUM,
                MediaStore.Video.Media.ARTIST)

        val offset = pageNumber * pageSize

        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .encodedQuery("limit=$offset,$pageSize")
                .build()

        return context.contentResolver
                .observeQuery(uri, projection, null,
                        null, MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")
                .mapToList {
                    fetchVideoFromCursor(it)
                }
                .toFlowable(BackpressureStrategy.LATEST)

    }

    private fun fetchVideoFromCursor(cursor: Cursor): Video {

        val video = Video()

        video.path = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DATA))

        video.name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME))

        video.mimeType = cursor.getString(cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE))

        video.size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE))

        var width = cursor.getInt(cursor.getColumnIndex(MediaStore.Video.Media.WIDTH))

        video.width = width

        var height = cursor.getInt(cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT))

        video.height = height

        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))

        video.id = id

        val contentUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())

        video.contentUri = contentUri

        video.album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.ALBUM))

        video.artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.ARTIST))

        try {

            context.contentResolver.openFileDescriptor(contentUri, "r").use { pfd ->

                val mediaMetadataRetriever = MediaMetadataRetriever()

                if (pfd != null) {

                    mediaMetadataRetriever.setDataSource(pfd.fileDescriptor)

                    if (width == 0 || height == 0) {

                        width = Integer.valueOf(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))

                        height = Integer.valueOf(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))

                        video.width = width

                        video.height = height

                    }

                    video.rotation = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()

                    when (video.rotation) {
                        90, 270 -> {
                            video.width = height
                            video.height = width
                        }
                    }

                    video.duration = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION).toLong()

                    video.album = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)

                    video.artist = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)

                }

                mediaMetadataRetriever.release()

            }

        } catch (e: IOException) {
            e.printStackTrace()
        }

        try {
            video.dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED))
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            video.dateAdded = 0
        }

        try {
            video.dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED))
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            video.dateModified = 0
        }

        return video

    }

    /*------------------------- Images -----------------------------*/

    fun loadImage(id: Long): Flowable<Image> {

        val projection = arrayOf(
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_MODIFIED)

        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val selection = MediaStore.Images.Media._ID + "=" + id

        return context.contentResolver
                .observeQuery(uri, projection, selection,
                        null, null)
                .mapToOne {
                    fetchImageFromCursor(it)
                }
                .toFlowable(BackpressureStrategy.LATEST)

    }

    fun loadImages(pageNumber: Int): @NonNull Flowable<List<Image>> {

        val projection = arrayOf(
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_MODIFIED)

        val offset = pageNumber * pageSize

        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .encodedQuery("limit=$offset,$pageSize")
                .build()

        return context.contentResolver
                .observeQuery(uri, projection, null,
                        null, MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")
                .mapToList {
                    fetchImageFromCursor(it)
                }
                .toFlowable(BackpressureStrategy.LATEST)

    }

    private fun fetchImageFromCursor(cursor: Cursor): Image {

        val image = Image()

        image.path = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DATA))

        image.name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))

        image.mimeType = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE))

        image.size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))

        val width = cursor.getInt(cursor.getColumnIndex(MediaStore.Images.Media.WIDTH))

        image.width = width

        val height = cursor.getInt(cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT))

        image.height = height

        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))

        image.id = (id)

        val contentUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
        image.contentUri = contentUri

        try {

            context.contentResolver.openFileDescriptor(contentUri, "r").use { pfd ->

                if (pfd != null) {

                    val exifInterface: ExifInterface = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        ExifInterface(pfd.fileDescriptor)
                    } else {
                        ExifInterface(image.path!!)
                    }

                    when (exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)) {

                        ExifInterface.ORIENTATION_ROTATE_180,
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                            image.rotation = 180
                        }

                        ExifInterface.ORIENTATION_TRANSVERSE,
                        ExifInterface.ORIENTATION_ROTATE_90 -> {

                            image.rotation = 90

                            image.height = width
                            image.width = height
                        }

                        ExifInterface.ORIENTATION_TRANSPOSE,
                        ExifInterface.ORIENTATION_ROTATE_270 -> {

                            image.rotation = 270

                            image.height = width
                            image.width = height
                        }

                        else -> image.rotation = 0
                    }

                }

            }

        } catch (e: IOException) {
            e.printStackTrace()
        }

        try {
            image.dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
        } catch (ex: Exception) {
            ex.printStackTrace()
            image.dateAdded = 0
        }

        try {
            image.dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED))
        } catch (ex: Exception) {
            ex.printStackTrace()
            image.dateModified = 0
        }

        return image

    }

    /*---------------------- Builder --------------------------------*/

    class Builder(private val context: Context) {

        private var pageSize: Int = DEFAULT_PAGE_SIZE

        fun build(): Submarine {
            val submarine = Submarine(context)
            submarine.pageSize = pageSize
            return submarine
        }

        fun setPageSize(pageSize: Int): Builder {
            this.pageSize = pageSize
            return this
        }

    }

    fun isVideo(mimeType: String): Boolean {
        return mimeType.contains("video")
    }

    fun isImage(mimeType: String): Boolean {
        return mimeType.contains("image")
    }

    fun isGif(mimeType: String): Boolean {
        return mimeType.contains("gif")
    }

}

