package app.getplexo.mobile.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import app.getplexo.mobile.PlexoApp
import app.getplexo.mobile.core.DownloadEntry
import app.getplexo.mobile.core.Settings
import app.getplexo.mobile.core.ThemeMode
import app.getplexo.mobile.service.DownloadService
import kotlinx.coroutines.flow.StateFlow

class PlexoViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as PlexoApp).downloads

    val settings: Settings = repo.settings
    val entries: StateFlow<List<DownloadEntry>> = repo.entries
    val links = repo.links.links
    val theme: StateFlow<ThemeMode> = settings.themeFlow

    /** Returns null when it was queued, or a user-facing error message. */
    fun start(
        rawUrl: String,
        name: String? = null,
        networks: Set<String>? = null,
        streams: Int? = null,
    ): String? {
        val url = rawUrl.trim()
        if (url.isEmpty()) return "Paste a link first"
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return "Only http:// and https:// links are supported"
        }
        DownloadService.start(
            context = getApplication(),
            url = url,
            name = name?.trim()?.takeIf { it.isNotBlank() },
            networks = networks,
            streams = streams,
        )
        return null
    }

    fun cancel(id: String) = repo.cancel(id)
    fun retry(id: String) = repo.retry(id)
    fun again(id: String) = repo.again(id)
    fun remove(id: String) = repo.remove(id)
    fun clearFinished() = repo.clearFinished()
    fun cancelAll() = repo.cancelAll()

    fun setLinkEnabled(id: String, enabled: Boolean) = repo.links.setEnabled(id, enabled)
    fun renameLink(id: String, name: String) = repo.links.rename(id, name)
    fun colorLink(id: String, color: Long) = repo.links.setColor(id, color)
}
