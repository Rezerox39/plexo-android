package app.getplexo.mobile

import android.app.Application
import app.getplexo.mobile.core.DownloadRepository

class PlexoApp : Application() {

    lateinit var downloads: DownloadRepository
        private set

    override fun onCreate() {
        super.onCreate()
        downloads = DownloadRepository(this)
    }
}
