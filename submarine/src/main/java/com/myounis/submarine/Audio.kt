package com.myounis.submarine

import android.graphics.Bitmap
import android.net.Uri

open class Audio constructor(var title: String? = null,
                             var artist: String? = null,
                             var album: String? = null,
                             var imageUri: Uri? = null,
                             var image:Bitmap? = null,
                             var duration: Long = 0,
                             var bitRate: Int = 0) : BaseMedia()