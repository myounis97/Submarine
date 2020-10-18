package com.myounis.submarine

import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.TextUtils
import android.webkit.MimeTypeMap
import androidx.annotation.Nullable
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import androidx.exifinterface.media.ExifInterface
import app.cash.copper.rx3.mapToList
import app.cash.copper.rx3.mapToOne
import app.cash.copper.rx3.observeQuery
import io.reactivex.rxjava3.annotations.NonNull
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger


class Submarine private constructor(private val context: Context) {

    companion object {
        const val DEFAULT_PAGE_SIZE = 15
    }

    enum class GalleryType {
        ALL, IMAGES, VIDEOS, IMAGES_VIDEOS, AUDIO
    }

    enum class SortBy {
        DESC, ASC
    }

    private var pageSize: Int = DEFAULT_PAGE_SIZE

    private val numOfCores = Runtime.getRuntime().availableProcessors()

    private val mDiskIO = Executors.newFixedThreadPool(numOfCores * 2, object : ThreadFactory {
        private val THREAD_NAME_STEM = "submarine_disk_io_%d"
        private val mThreadId = AtomicInteger(0)

        override fun newThread(r: Runnable): Thread {
            val t = Thread(r)
            t.name = String.format(THREAD_NAME_STEM, mThreadId.getAndIncrement())
            return t
        }
    })

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

            GalleryType.ALL -> (MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                    + " OR "
                    + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    + " OR "
                    + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO)

        }

        val uri = MediaStore.Files.getContentUri("external")

        return context.contentResolver
            .observeQuery(
                uri, projection, selection,
                null, null
            )
            .mapToList {
                fetchAlbumFromCursor(it)
            }
            .toFlowable(BackpressureStrategy.BUFFER)

    }

    private fun fetchAlbumFromCursor(cursor: Cursor): Album {

        val id = cursor.getLong(cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_ID))

        val name =
            cursor.getString(cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME))

        return Album(id, name)

    }

    /*------------------------- Images and Videos -----------------------------*/

    fun loadMedia(
        pageNumber: Int,
        @Nullable album: Album? = null,
        sortBy: SortBy = SortBy.DESC
    ): @NonNull Flowable<List<BaseMedia>> {

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

        var selection =
            "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE +
                    " OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO +
                    " OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO + ")"

        if (album != null)
            selection = " AND " + MediaStore.Files.FileColumns.BUCKET_ID + "=" + album.id

        return context.contentResolver
            .observeQuery(
                uri, projection, selection,
                null, MediaStore.Files.FileColumns.DATE_MODIFIED + " ${sortBy.name}"
            )
            .subscribeOn(Schedulers.io())
            .mapToList {
                fetchMediaFromCursor(it)
            }
            .flatMap { it ->
                Observable.fromIterable(it)
                    .flatMap { baseMedia ->
                        Observable.just(baseMedia)
                            .subscribeOn(Schedulers.from(mDiskIO))
                            .map { extractMediaMetadata(it) }
                    }
                    .toList()
                    .toObservable()
            }
            .toFlowable(BackpressureStrategy.BUFFER)

    }

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

        val selection =
            "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE +
                    " OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO +
                    " OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO + ")" +
                    " AND " + MediaStore.Files.FileColumns._ID + " IN ( " + TextUtils.join(
                ",",
                ids
            ) + " )"

        return context.contentResolver
            .observeQuery(
                uri, projection, selection,
                null, null
            )
            .mapToList {
                fetchMediaFromCursor(it)
            }
            .flatMap { it ->
                Observable.fromIterable(it)
                    .flatMap { baseMedia ->
                        Observable.just(baseMedia)
                            .subscribeOn(Schedulers.from(mDiskIO))
                            .map { extractMediaMetadata(it) }
                    }
                    .toList()
                    .toObservable()
            }
            .toFlowable(BackpressureStrategy.BUFFER)

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

        val selection =
            "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE +
                    " OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO +
                    " OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO + ")" +
                    " AND " + MediaStore.Files.FileColumns._ID + "=" + "'$id'"

        return context.contentResolver
            .observeQuery(
                uri, projection, selection,
                null, null
            )
            .mapToOne {
                fetchMediaFromCursor(it)
            }
            .map { extractMediaMetadata(it) }
            .toFlowable(BackpressureStrategy.LATEST)

    }

    fun loadImagesAndVideos(
        pageNumber: Int,
        @Nullable album: Album? = null,
        sortBy: SortBy = SortBy.DESC
    ): @NonNull Flowable<List<BaseMedia>> {

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

        var selection =
            "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE +
                    " OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")"

        if (album != null) {
            selection += " AND " + MediaStore.Files.FileColumns.BUCKET_ID + "=" + album.id
        }

        return context.contentResolver
            .observeQuery(
                uri, projection, selection,
                null, MediaStore.Files.FileColumns.DATE_MODIFIED + " ${sortBy.name}"
            )
            .mapToList {
                val media = fetchMediaFromCursor(it)

                checkWidthAndHeight(media)

                return@mapToList media
            }
            .flatMap { it ->
                Observable.fromIterable(it)
                    .flatMap { baseMedia ->
                        Observable.just(baseMedia)
                            .subscribeOn(Schedulers.from(mDiskIO))
                            .map { extractMediaMetadata(it) }
                    }
                    .toList()
                    .toObservable()
            }
            .toFlowable(BackpressureStrategy.BUFFER)

    }

    private fun fetchMediaFromCursor(cursor: Cursor): BaseMedia {

        val mimeType = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE))

        return when {
            isVideo(mimeType) ->
                fetchVideoFromCursor(cursor)

            isImage(mimeType) ->
                fetchImageFromCursor(cursor)

            else ->
                fetchAudioFromCursor(cursor)
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
            MediaStore.Audio.Media.ARTIST_ID
        )

        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val selection = MediaStore.Audio.Media._ID + "=" + id

        return context.contentResolver
            .observeQuery(
                uri, projection, selection,
                null, null
            )
            .mapToOne { fetchAudioFromCursor(it) }
            .map { extractAudioMetadata(it) }
            .toFlowable(BackpressureStrategy.LATEST)

    }

    fun loadAudios(
        pageNumber: Int,
        @Nullable album: Album? = null,
        sortBy: SortBy = SortBy.DESC
    ): @NonNull Flowable<List<Audio>> {

        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.AudioColumns.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.ARTIST_ID
        )

        val offset = pageNumber * pageSize

        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
            .encodedQuery("limit=$offset,$pageSize")
            .build()

        var selection: String? = null

        if (album != null)
            selection = MediaStore.Files.FileColumns.BUCKET_ID + "=" + album.id

        return context.contentResolver
            .observeQuery(
                uri, projection, selection,
                null, MediaStore.Files.FileColumns.DATE_MODIFIED + " ${sortBy.name}"
            )
            .mapToList {
                fetchAudioFromCursor(it)
            }
            .flatMap { it ->
                Observable.fromIterable(it)
                    .flatMap { baseMedia ->
                        Observable.just(baseMedia)
                            .subscribeOn(Schedulers.from(mDiskIO))
                            .map { extractAudioMetadata(it) }
                    }
                    .toList()
                    .toObservable()
            }
            .toFlowable(BackpressureStrategy.BUFFER)

    }

    private fun fetchAudioFromCursor(cursor: Cursor): Audio {

        val audio = Audio()

        audio.path = cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.DATA))

        val songName = cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME))

        audio.name = songName

        audio.mimeType = cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE))

        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(audio.mimeType)

        audio.extension = extension

        val songTitle = cursor.getStringOrNull(cursor.getColumnIndex(MediaStore.Audio.Media.TITLE))
        audio.title = songTitle

        val id = cursor.getLong(cursor.getColumnIndex(MediaStore.Audio.Media._ID))
        audio.id = id

        val contentUri =
            Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
        audio.contentUri = contentUri

        audio.size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))

        val albumName = cursor.getStringOrNull(cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM))
        audio.album = albumName

        val artistName =
            cursor.getStringOrNull(cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST))
        audio.artist = artistName

        val albumId = cursor.getLongOrNull(cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID))

        if (albumId != null) {

            val sArtworkUri = Uri.parse("content://media/external/audio/albumart")

            val imageUri = Uri.withAppendedPath(sArtworkUri, albumId.toString())

            audio.imageUri = imageUri

        }

        try {
            audio.dateAdded =
                cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED))
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            audio.dateAdded = 0
        }

        try {
            audio.dateModified =
                cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED))
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            audio.dateModified = 0
        }

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
            MediaStore.Video.Media.ARTIST
        )

        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val selection = MediaStore.Video.Media._ID + "=" + id

        return context.contentResolver
            .observeQuery(
                uri, projection, selection,
                null, null
            )
            .mapToOne { fetchVideoFromCursor(it) }
            .map { extractVideoMetadata(it) }
            .toFlowable(BackpressureStrategy.LATEST)

    }

    fun loadVideos(
        pageNumber: Int,
        @Nullable album: Album? = null,
        sortBy: SortBy = SortBy.DESC
    ): @NonNull Flowable<List<Video>> {

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
            MediaStore.Video.Media.ARTIST
        )

        val offset = pageNumber * pageSize

        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.buildUpon()
            .encodedQuery("limit=$offset,$pageSize")
            .build()

        var selection: String? = null

        if (album != null)
            selection = MediaStore.Files.FileColumns.BUCKET_ID + "=" + album.id

        return context.contentResolver
            .observeQuery(
                uri, projection, selection,
                null, MediaStore.Files.FileColumns.DATE_MODIFIED + " ${sortBy.name}"
            )
            .mapToList {
                fetchVideoFromCursor(it)
            }
            .flatMap { it ->
                Observable.fromIterable(it)
                    .flatMap { baseMedia ->
                        Observable.just(baseMedia)
                            .subscribeOn(Schedulers.from(mDiskIO))
                            .map { extractVideoMetadata(it) }
                    }
                    .toList()
                    .toObservable()
            }
            .toFlowable(BackpressureStrategy.BUFFER)

    }

    private fun fetchVideoFromCursor(cursor: Cursor): Video {

        val video = Video()

        video.path = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DATA))

        video.name =
            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME))

        video.mimeType = cursor.getString(cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE))

        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(video.mimeType)

        video.extension = extension

        video.size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE))

        val width = cursor.getInt(cursor.getColumnIndex(MediaStore.Video.Media.WIDTH))

        video.width = width

        val height = cursor.getInt(cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT))

        video.height = height

        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))

        video.id = id

        val contentUri =
            Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())

        video.contentUri = contentUri

        video.album = cursor.getStringOrNull(cursor.getColumnIndex(MediaStore.Video.Media.ALBUM))

        video.artist = cursor.getStringOrNull(cursor.getColumnIndex(MediaStore.Video.Media.ARTIST))

        try {
            video.dateAdded =
                cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED))
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            video.dateAdded = 0
        }

        try {
            video.dateModified =
                cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED))
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
            MediaStore.Images.Media.DATE_MODIFIED
        )

        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val selection = MediaStore.Images.Media._ID + "=" + id

        return context.contentResolver
            .observeQuery(
                uri, projection, selection,
                null, null
            )
            .mapToOne { fetchImageFromCursor(it) }
            .map { extractImageMetadata(it) }
            .toFlowable(BackpressureStrategy.LATEST)

    }

    fun loadImages(
        pageNumber: Int,
        @Nullable album: Album? = null,
        sortBy: SortBy = SortBy.DESC
    ): @NonNull Flowable<List<Image>> {

        val projection = arrayOf(
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        val offset = pageNumber * pageSize

        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
            .encodedQuery("limit=$offset,$pageSize")
            .build()

        var selection: String? = null

        if (album != null)
            selection = MediaStore.Files.FileColumns.BUCKET_ID + "=" + album.id

        return context.contentResolver
            .observeQuery(
                uri, projection, selection,
                null, MediaStore.Files.FileColumns.DATE_MODIFIED + " ${sortBy.name}"
            )
            .mapToList {
                fetchImageFromCursor(it)
            }
            .flatMap { it ->
                Observable.fromIterable(it)
                    .flatMap { baseMedia ->
                        Observable.just(baseMedia)
                            .subscribeOn(Schedulers.from(mDiskIO))
                            .map { extractImageMetadata(it) }
                    }
                    .toList()
                    .toObservable()
            }
            .toFlowable(BackpressureStrategy.BUFFER)

    }

    private fun fetchImageFromCursor(cursor: Cursor): Image {

        val image = Image()

        image.path = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DATA))

        image.name =
            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))

        image.mimeType = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE))

        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(image.mimeType)

        image.isGif =
            if (image.mimeType != null) {
                isGif(image.mimeType!!)
            } else {
                false
            }

        image.extension = extension

        image.size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))

        val width = cursor.getInt(cursor.getColumnIndex(MediaStore.Images.Media.WIDTH))

        image.width = width

        val height = cursor.getInt(cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT))

        image.height = height

        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))

        image.id = (id)

        val contentUri =
            Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
        image.contentUri = contentUri

        try {
            image.dateAdded =
                cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
        } catch (ex: Exception) {
            ex.printStackTrace()
            image.dateAdded = 0
        }

        try {
            image.dateModified =
                cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED))
        } catch (ex: Exception) {
            ex.printStackTrace()
            image.dateModified = 0
        }

        return image

    }

    /*---------------------- Media Metadata --------------------------------*/

    private fun extractMediaMetadata(baseMedia: BaseMedia): BaseMedia {

        baseMedia.mimeType?.let {

            return when {

                isImage(it) -> extractImageMetadata(baseMedia as Image)

                isVideo(it) -> extractVideoMetadata(baseMedia as Video)

                else -> extractAudioMetadata(baseMedia as Audio)

            }

        }

        return baseMedia

    }

    private fun extractAudioMetadata(audio: Audio): Audio {

        try {
            audio.contentUri?.let { uri ->
                context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->

                    if (pfd != null) {

                        val mediaMetadataRetriever = MediaMetadataRetriever()

                        mediaMetadataRetriever.setDataSource(
                            pfd.fileDescriptor
                        )

                        if (audio.title == null)
                            audio.title =
                                mediaMetadataRetriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_TITLE
                                )

                        if (audio.album == null)
                            audio.album =
                                mediaMetadataRetriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_ALBUM
                                )

                        if (audio.artist == null)
                            audio.artist =
                                mediaMetadataRetriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_ARTIST
                                )

                        if (audio.imageUri == null) {

                            val imageByteArray =
                                mediaMetadataRetriever.embeddedPicture

                            if (imageByteArray != null) {

                                val image = BitmapFactory.decodeByteArray(
                                    imageByteArray,
                                    0,
                                    imageByteArray.size
                                )

                                audio.image = image

                            }

                        }

                        audio.duration =
                            mediaMetadataRetriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_DURATION
                            ).toLong()

                        audio.bitRate =
                            mediaMetadataRetriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_BITRATE
                            ).toInt()

                        mediaMetadataRetriever.release()

                    }
                }
            }
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
        }

        return audio

    }

    private fun extractVideoMetadata(video: Video): Video {

        try {
            video.contentUri?.let { uri ->
                context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->

                    if (pfd != null) {

                        var width = video.width

                        var height = video.height

                        val mediaMetadataRetriever = MediaMetadataRetriever()

                        mediaMetadataRetriever.setDataSource(
                            pfd.fileDescriptor
                        )

                        if (width == 0 || height == 0) {

                            width = Integer.valueOf(
                                mediaMetadataRetriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                                )
                            )

                            height = Integer.valueOf(
                                mediaMetadataRetriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                                )
                            )

                            video.width = width

                            video.height = height

                        }

                        video.rotation =
                            mediaMetadataRetriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                            ).toInt()

                        when (video.rotation) {
                            90, 270 -> {
                                video.width = height
                                video.height = width
                            }
                        }

                        video.duration =
                            mediaMetadataRetriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_DURATION
                            ).toLong()

                        video.album =
                            mediaMetadataRetriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_ALBUM
                            )

                        video.artist =
                            mediaMetadataRetriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_ARTIST
                            )

                        mediaMetadataRetriever.release()
                    }
                }
            }
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
        }

        checkWidthAndHeight(video)

        return video

    }

    private fun extractImageMetadata(image: Image): Image {

        try {

            image.contentUri?.let { uri ->
                context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->

                    if (pfd != null) {

                        var width = image.width

                        var height = image.height

                        if (image.width == 0 || image.height == 0) {

                            context.contentResolver.openFileDescriptor(image.contentUri!!, "r")
                                .use { parcelFileDescriptor ->

                                    if (parcelFileDescriptor != null) {

                                        val options = BitmapFactory.Options()

                                        options.inJustDecodeBounds = true

                                        BitmapFactory.decodeFileDescriptor(
                                            parcelFileDescriptor.fileDescriptor,
                                            null,
                                            options
                                        )

                                        width = options.outWidth

                                        height = options.outHeight

                                        image.width = width

                                        image.height = height

                                    }

                                }

                        }

                        val exifInterface: ExifInterface =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                ExifInterface(pfd.fileDescriptor)
                            } else {
                                ExifInterface(image.path!!)
                            }

                        when (exifInterface.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_UNDEFINED
                        )) {

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
            }

        } catch (e: IOException) {
            e.printStackTrace()
        }


        checkWidthAndHeight(image)

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

    fun isAudio(mimeType: String): Boolean {
        return mimeType.contains("audio")
    }

}

