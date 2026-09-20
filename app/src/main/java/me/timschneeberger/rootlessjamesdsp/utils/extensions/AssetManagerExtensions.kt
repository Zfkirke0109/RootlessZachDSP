package me.timschneeberger.rootlessjamesdsp.utils.extensions

import android.content.Context
import android.content.res.AssetManager
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

object AssetManagerExtensions {
    fun AssetManager.installPrivateAssets(context: Context, force: Boolean) {
        Timber.d("Installing private assets; force=$force")

        val destinationRoot = context.getExternalFilesDir(null) ?: context.filesDir
        if (!destinationRoot.exists() && !destinationRoot.mkdirs()) {
            Timber.e("Unable to create private asset directory: ${destinationRoot.absolutePath}")
            return
        }
        if (!destinationRoot.isDirectory) {
            Timber.e("Private asset path is not a directory: ${destinationRoot.absolutePath}")
            return
        }

        try {
            this.copyAssetDir("Convolver", destinationRoot.absolutePath, force)
            this.copyAssetDir("DDC", destinationRoot.absolutePath, force)
            this.copyAssetDir("Liveprog", destinationRoot.absolutePath, force)
        }
        catch (ex: Exception) {
            Timber.e(ex, "Failed to extract private assets into ${destinationRoot.absolutePath}")
        }
    }

    private fun AssetManager.copyAssetDir(assetPath: String, destDirPath: String, force: Boolean) {
        this.walkAssetDir(assetPath) {
            this.copyAssetFile(it, "$destDirPath/$it", force)
        }
    }

    private fun AssetManager.walkAssetDir(assetPath: String, callback: ((String) -> Unit)) {
        val children = this.list(assetPath) ?: return
        if (children.isEmpty()) {
            callback(assetPath)
        } else {
            for (child in children) {
                this.walkAssetDir("$assetPath/$child", callback)
            }
        }
    }

    private fun AssetManager.copyAssetFile(assetPath: String, destPath: String, force: Boolean): File? {
        val destFile = File(destPath)
        if(destFile.exists() && !force) {
            return null
        }

        val parent = destFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("Unable to create asset directory: ${parent.absolutePath}")
        }
        if (parent?.isDirectory == false) {
            throw IllegalStateException("Asset parent is not a directory: ${parent.absolutePath}")
        }

        this.open(assetPath).use { src ->
            FileOutputStream(destFile, false).use { dest ->
                src.copyTo(dest)
                dest.fd.sync()
            }
        }

        return destFile
    }
}
