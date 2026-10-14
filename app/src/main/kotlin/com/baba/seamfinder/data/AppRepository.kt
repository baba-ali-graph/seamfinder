package com.baba.seamfinder.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Process
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Supplies both the "recently used" and "every installed app" lists for the search panel.
 *
 * The full launchable-app catalog is kept in memory after the first load (and refreshed when
 * packages change), so opening the panel does not re-scan PackageManager every time. Icons are
 * also cached as bitmaps for the process lifetime.
 */
class AppRepository(private val context: Context) {

    private val iconCache = LruCache<String, ImageBitmap>(120)
    private val loadMutex = Mutex()

    @Volatile
    private var allAppsCache: List<AppEntry>? = null

    @Volatile
    private var recentCache: List<AppEntry> = emptyList()

    private var packageMonitorRegistered = false

    private val packageMonitor = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            invalidateAllApps()
        }
    }

    fun cachedAllApps(): List<AppEntry> = allAppsCache.orEmpty()

    fun cachedRecentApps(): List<AppEntry> = recentCache

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun registerPackageMonitor() {
        if (packageMonitorRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            context,
            packageMonitor,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        packageMonitorRegistered = true
    }

    fun unregisterPackageMonitor() {
        if (!packageMonitorRegistered) return
        runCatching { context.unregisterReceiver(packageMonitor) }
        packageMonitorRegistered = false
    }

    fun invalidateAllApps() {
        allAppsCache = null
    }

    /** Prefetch the catalog (and top recents) so the first panel open is warm. */
    suspend fun warm() {
        val apps = allLaunchableApps()
        apps.take(24).forEach { iconBitmap(it) }
        recentApps()
    }

    suspend fun recentApps(limit: Int = RECENT_LIMIT): List<AppEntry> = withContext(Dispatchers.IO) {
        if (!hasUsageAccess()) {
            recentCache = emptyList()
            return@withContext emptyList()
        }

        val packageManager = context.packageManager
        val ownPackage = context.packageName
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - USAGE_WINDOW_MS
        val cachedByPackage = allAppsCache?.associateBy { it.packageName }

        val result = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
            .asSequence()
            .filter { it.lastTimeUsed > 0 && it.packageName != ownPackage }
            .sortedByDescending { it.lastTimeUsed }
            .distinctBy { it.packageName }
            .mapNotNull { stats ->
                cachedByPackage?.get(stats.packageName)?.copy(lastUsedMillis = stats.lastTimeUsed)
                    ?: toAppEntry(packageManager, stats.packageName, stats.lastTimeUsed)
            }
            .take(limit)
            .toList()

        recentCache = result
        result.forEach { iconBitmap(it) }
        result
    }

    suspend fun allLaunchableApps(forceRefresh: Boolean = false): List<AppEntry> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                allAppsCache?.let { return@withContext it }
            }
            loadMutex.withLock {
                if (!forceRefresh) {
                    allAppsCache?.let { return@withContext it }
                }
                val loaded = loadAllLaunchableApps()
                allAppsCache = loaded
                loaded
            }
        }

    fun iconBitmap(entry: AppEntry): ImageBitmap {
        iconCache.get(entry.packageName)?.let { return it }
        return try {
            val bitmap = entry.icon.toBitmap().asImageBitmap()
            iconCache.put(entry.packageName, bitmap)
            bitmap
        } catch (_: Exception) {
            // Some OEM icons throw on decode; surface a 1x1 placeholder instead of crashing.
            ImageBitmap(width = 1, height = 1)
        }
    }

    private fun loadAllLaunchableApps(): List<AppEntry> {
        val packageManager = context.packageManager
        val ownPackage = context.packageName
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        return packageManager.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
            .asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != ownPackage }
            .distinctBy { it.packageName }
            .mapNotNull { activityInfo ->
                try {
                    val appInfo = activityInfo.applicationInfo
                    AppEntry(
                        packageName = activityInfo.packageName,
                        label = appInfo.loadLabel(packageManager).toString(),
                        icon = appInfo.loadIcon(packageManager),
                        lastUsedMillis = null,
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun toAppEntry(
        packageManager: PackageManager,
        packageName: String,
        lastUsedMillis: Long?,
    ): AppEntry? {
        if (packageManager.getLaunchIntentForPackage(packageName) == null) return null
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            AppEntry(
                packageName = packageName,
                label = appInfo.loadLabel(packageManager).toString(),
                icon = appInfo.loadIcon(packageManager),
                lastUsedMillis = lastUsedMillis,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    companion object {
        private const val USAGE_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L
        const val RECENT_LIMIT = 9
    }
}
