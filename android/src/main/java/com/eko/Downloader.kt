package com.eko

import android.content.Context
import android.util.Log

import com.tonyodev.fetch2.Fetch
import com.tonyodev.fetch2.FetchConfiguration
import com.tonyodev.fetch2.HttpUrlConnectionDownloader
import com.tonyodev.fetch2.Request


class Downloader(private val context: Context) {
  init {
    val fetchConfiguration: FetchConfiguration = FetchConfiguration.Builder(this.context)
      .setDownloadConcurrentLimit(3)
      .setHttpDownloader(HttpUrlConnectionDownloader(com.tonyodev.fetch2core.Downloader.FileDownloaderType.PARALLEL))
      .build()
    Fetch.Impl.setDefaultInstanceConfiguration(fetchConfiguration)
  }

  fun download(request: Request): Long {
    Log.d("Downloader", "will download (enqueue): " + request.file)

    Fetch.Impl.getDefaultInstance().enqueue(request, { updatedRequest ->
      //Request was successfully enqueued for download.
      Log.d("Downloader", "is now enqueued with requestId: " + updatedRequest.id)
    }, { error ->
      //An error occurred enqueuing the request.
      Log.d("Downloader", "failed to enqueue: $error")
    })

    return request.id.toLong()
  }

  fun cancel(downloadId: Long) {
    val fetch: Fetch = Fetch.Impl.getDefaultInstance()
    fetch.cancel(downloadId.toInt())
    fetch.delete(downloadId.toInt())
  }

  fun pause(downloadId: Long) {
    Fetch.Impl.getDefaultInstance().pause(downloadId.toInt());
  }

  fun resume(downloadId: Long) {
    Fetch.Impl.getDefaultInstance().resume(downloadId.toInt());
  }
}
