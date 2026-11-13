package app.getplexo.mobile.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import app.getplexo.mobile.PlexoApp
import app.getplexo.mobile.core.DownloadSnapshot
import app.getplexo.mobile.core.Settings
import app.getplexo.mobile.service.DownloadService
import kotlinx.coroutines.flow.StateFlow

class PlexoViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as PlexoApp).downloads

    val settings = Settings(application)
    val download: StateFlow<DownloadSnapshot?> = repo.state
    val links = repo.links.links

    /** Returns null when the download started, or a user facing error message. */
    fun start(rawUrl: String, name: String? = null): String? {
        val url = rawUrl.trim()
        if (url.isEmpty()) return "Paste a link first"
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return "Only http:// and https:// links are supported"
        }
        if (repo.busy) return "A download is already running"

        DownloadService.start(
            context = getApplication(),
            url = url,
            name = name,
            streams = settings.streamsPerLink,
            chunkMb = settings.chunkMb,
        )
        return null
    }

    fun cancel() = DownloadService.cancel(getApplication())

    fun dismiss() = repo.dismiss()

    fun setLinkEnabled(id: String, enabled: Boolean) = repo.links.setEnabled(id, enabled)
}
