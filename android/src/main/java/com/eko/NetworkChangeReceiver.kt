package com.eko

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.tonyodev.fetch2.Download
import com.tonyodev.fetch2.Fetch.Impl.getDefaultInstance
import com.tonyodev.fetch2.Status
import com.tonyodev.fetch2core.Func

class NetworkChangeReceiver(private val downloaderImpl: RNBackgroundDownloaderModuleImpl) :
    BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Network change detected")

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        if (cm == null) return

        val activeNetwork = cm.activeNetwork
        if (activeNetwork == null) {
            Log.d(TAG, "No active network")
            return
        }

        val capabilities = cm.getNetworkCapabilities(activeNetwork)
        val isWifi =
            capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular =
            capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        val approved: Boolean = downloaderImpl.getIsApproved()
        Log.d(TAG, "isWifi: " + isWifi + ", isCellular: " + isCellular + ", approved: " + approved)

        if (isWifi) {
            resumeAllDownloads()
        } else if (isCellular) {
            if (approved) {
                resumeAllDownloads()
            } else {
                cancelAllDownloads()
            }
        }
    }

    private fun resumeAllDownloads() {
        val fetch = getDefaultInstance()
        fetch.getDownloads(Func { downloads: List<Download>? ->
            for (download in downloads!!) {
                if (download.status == Status.DOWNLOADING || download.status == Status.QUEUED) {
                    fetch.pause(download.id)
                    fetch.resume(download.id)
                    Log.d(TAG, "Resuming download: " + download.id)
                }
            }
        })
    }

    private fun cancelAllDownloads() {
        val fetch = getDefaultInstance()
        fetch.getDownloads(Func { downloads: List<Download>? ->
            for (download in downloads!!) {
                if (download.status == Status.DOWNLOADING || download.status == Status.QUEUED) {
                    fetch.cancel(download.id)
                    fetch.delete(download.id)
                    Log.d(TAG, "Cancelling download: " + download.id)
                }
            }
        })
    }

    companion object {
        private const val TAG = "NetworkChangeReceiver"
    }
}