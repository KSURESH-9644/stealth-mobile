package com.stealth.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val GITHUB_REPO = "KSURESH-9644/stealth-assistant-mobile-app"
    private val client = OkHttpClient()

    fun checkForUpdate(context: Context, onStatusUpdate: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) { onStatusUpdate("Checking for updates...") }

                val currentVersion = getCurrentVersion(context)
                val url = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) { onStatusUpdate("No updates found on GitHub") }
                    return@launch
                }

                val jsonData = response.body?.string() ?: return@launch
                val json = JSONObject(jsonData)
                val latestTag = json.getString("tag_name")

                if (isNewerVersion(latestTag, currentVersion)) {
                    val assets = json.getJSONArray("assets")
                    var downloadUrl = ""

                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.getString("name").endsWith(".apk")) {
                            downloadUrl = asset.getString("browser_download_url")
                            break
                        }
                    }

                    if (downloadUrl.isNotEmpty()) {
                        withContext(Dispatchers.Main) { onStatusUpdate("Downloading update $latestTag...") }
                        downloadAndInstallApk(context, downloadUrl, onStatusUpdate)
                    } else {
                        withContext(Dispatchers.Main) { onStatusUpdate("Update found, but no APK attached") }
                    }
                } else {
                    withContext(Dispatchers.Main) { onStatusUpdate("You are on the latest version ($currentVersion)") }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Update check failed: ${e.message}")
                withContext(Dispatchers.Main) { onStatusUpdate("Update check failed: ${e.message}") }
            }
        }
    }

    private fun getCurrentVersion(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            "v${packageInfo.versionName}"
        } catch (e: Exception) {
            "v0.0.0"
        }
    }

    // Semantic Version Comparison (ఉదా: v1.0.10 > v1.0.9)
    private fun isNewerVersion(latest: String, current: String): Boolean {
        try {
            val l = latest.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
            val c = current.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }

            val maxLen = maxOf(l.size, c.size)
            for (i in 0 until maxLen) {
                val lPart = l.getOrElse(i) { 0 }
                val cPart = c.getOrElse(i) { 0 }
                if (lPart > cPart) return true
                if (lPart < cPart) return false
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun downloadAndInstallApk(context: Context, url: String, onStatusUpdate: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                val destinationDir = context.getExternalFilesDir(null)
                val apkFile = File(destinationDir, "update.apk")

                response.body?.byteStream()?.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }

                withContext(Dispatchers.Main) {
                    onStatusUpdate("Download complete! Launching install...")
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatusUpdate("Download failed: ${e.message}") }
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}