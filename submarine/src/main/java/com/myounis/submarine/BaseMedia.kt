package com.myounis.submarine

import android.net.Uri

open class BaseMedia(
        var id: Long = 0,
        var path: String? = null,
        var name: String? = null,
        var contentUri: Uri? = null,
        var size: Long = 0,
        var width: Int = 0,
        var height: Int = 0,
        var dateAdded: Long = 0,
        var dateModified: Long = 0,
        var mimeType: String? = null,
        var rotation: Int = -1,
        var extension: String? = null
)