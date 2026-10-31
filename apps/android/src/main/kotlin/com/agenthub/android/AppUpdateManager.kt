package com.agenthub.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

internal data class AppUpdateInfo(
    val tag: String,
    val name: String,
    val assetUrl: String,
    val assetName: String,
    val size: Long,
    val versionName: String,
    val versionCode: Int,
)

internal class AppUpdateManager(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val releasesUrl =
        "https://api.github.com/repos/wang1733238039/codex-mobile/releases?per_page=10"

    private fun parseVersion(value: String): List<Int>? {
        val match = Regex("""(?:^|[^0-9])(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")
            .find(value.trim()) ?: return null
        return match.groupValues.drop(1).take(3).map { it.toIntOrNull() ?: return null }
    }

    private fun compareVersions(left: List<Int>, right: List<Int>): Int =
        (0..2).firstOrNull { index -> left[index] != right[index] }
            ?.let { index -> left[index].compareTo(right[index]) }
            ?: 0

    private fun releaseVersionCode(body: String): Int? =
        Regex("""(?im)^\s*codex-mobile-version-code\s*:\s*(\d+)\s*$""")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    suspend fun check(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(releasesUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CodexMobileUpdater")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("检查更新失败（HTTP ${response.code}）")
            val releases = org.json.JSONArray(response.body?.string().orEmpty())
            val currentCode = BuildConfig.VERSION_CODE
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: continue
                if (release.optBoolean("draft", false)) continue
                val assets = release.optJSONArray("assets") ?: continue
                val asset = (0 until assets.length())
                    .mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull {
                        it.optString("name").lowercase().endsWith(".apk") &&
                            it.optString("browser_download_url").startsWith("https://")
                    } ?: continue
                val tag = release.optString("tag_name")
                val remoteVersion = parseVersion(tag) ?: parseVersion(release.optString("name"))
                    ?: continue
                val currentVersion = parseVersion(BuildConfig.VERSION_NAME) ?: listOf(0, 0, 0)
                val remoteCode = release.optInt("version_code", -1).takeIf { it > 0 }
                    ?: releaseVersionCode(release.optString("body"))
                    ?: currentCode
                val newerVersion = compareVersions(remoteVersion, currentVersion) > 0
                val newerBuild = remoteVersion == currentVersion && remoteCode > currentCode
                if (!newerVersion && !newerBuild) return@withContext null
                return@withContext AppUpdateInfo(
                    tag = tag,
                    name = release.optString("name", tag),
                    assetUrl = asset.optString("browser_download_url"),
                    assetName = asset.optString("name"),
                    size = asset.optLong("size", 0L),
                    versionName = remoteVersion.joinToString("."),
                    versionCode = remoteCode,
                )
            }
            null
        }
    }

    suspend fun download(info: AppUpdateInfo): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "codex-mobile-update.apk")
        val partial = File(directory, "codex-mobile-update.apk.part")
        val request = Request.Builder()
            .url(info.assetUrl)
            .header("Accept", "application/vnd.android.package-archive")
            .header("User-Agent", "CodexMobileUpdater")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载更新失败（HTTP ${response.code}）")
            val body = response.body ?: error("更新文件为空")
            partial.delete()
            partial.outputStream().use { output -> body.byteStream().use { input -> input.copyTo(output) } }
        }
        if (!partial.isFile || partial.length() == 0L) {
            partial.delete()
            error("更新文件为空")
        }
        if (info.size > 0L && partial.length() != info.size) {
            partial.delete()
            error("更新文件大小校验失败")
        }
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            error("更新文件保存失败")
        }
        target
    }

    fun install(file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.preview-files",
            file,
        )
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        return true
    }
}
