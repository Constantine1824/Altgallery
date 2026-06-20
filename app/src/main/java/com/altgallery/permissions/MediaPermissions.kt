package com.altgallery.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permission needed to read the photo library, and how to check it.
 *
 * The required permission changed across Android versions:
 *  - API 33+ (Tiramisu): READ_MEDIA_IMAGES
 *  - API 26-32:           READ_EXTERNAL_STORAGE
 *
 * (POST_NOTIFICATIONS, for processing progress, is requested separately in M4/M8.)
 *
 * This is a plain utility usable from both a ViewModel and a Composable; the
 * actual request UI (rememberLauncherForActivityResult / rationale dialog) is
 * wired up with the Home screen in M7.
 */
object MediaPermissions {

    /** The permission string to request on this device. */
    val readImages: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasReadImages(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, readImages) == PackageManager.PERMISSION_GRANTED
}
