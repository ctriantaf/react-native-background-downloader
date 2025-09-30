package com.eko

import android.app.DownloadManager
import android.content.Context
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.edit
import com.eko.utils.FileUtils
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import com.tonyodev.fetch2.AbstractFetchListener
import com.tonyodev.fetch2.Download
import com.tonyodev.fetch2.Fetch
import com.tonyodev.fetch2.Request
import com.tonyodev.fetch2.Status
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class RNBackgroundDownloaderModuleImpl(reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext), LifecycleEventListener {
  private val fixedExecutorPool: ExecutorService = Executors.newFixedThreadPool(1)
  private val downloader: Downloader
  private var downloadIdToConfig: MutableMap<Long, RNBGDTaskConfig?> =
    HashMap<Long, RNBGDTaskConfig?>()
  private val configIdToDownloadId: MutableMap<String?, Long?> = HashMap<String?, Long?>()
  private var progressInterval = 0
  private var progressMinBytes = (1024 * 1024 // Default 1MB
    ).toLong()
  private var ee: DeviceEventManagerModule.RCTDeviceEventEmitter? = null

  private var networkChangeReceiver: NetworkChangeReceiver? = null

  init {
    // Initialize SharedPreferences as fallback
    sharedPreferences =
      reactContext.getSharedPreferences(getName() + "_prefs", Context.MODE_PRIVATE)


    // Try to initialize MMKV with comprehensive error handling
    try {
      MMKV.initialize(reactContext)
      mmkv = MMKV.mmkvWithID(getName())
      isMMKVAvailable = true
      Log.d(getName(), "MMKV initialized successfully")
    } catch (e: UnsatisfiedLinkError) {
      Log.e(getName(), "Failed to initialize MMKV (libmmkv.so not found): " + e.message)
      Log.w(
        getName(),
        "This may be due to unsupported architecture (x86/ARMv7). Using SharedPreferences fallback."
      )
      Log.w(getName(), "Download persistence across app restarts will use basic storage.")
      mmkv = null
      isMMKVAvailable = false
    } catch (e: NoClassDefFoundError) {
      Log.e(getName(), "MMKV classes not found: " + e.message)
      Log.w(
        getName(),
        "MMKV library not available on this architecture. Using SharedPreferences fallback."
      )
      mmkv = null
      isMMKVAvailable = false
    } catch (e: Exception) {
      Log.e(getName(), "Failed to initialize MMKV: " + e.message)
      Log.w(getName(), "Using SharedPreferences fallback for persistence.")
      mmkv = null
      isMMKVAvailable = false
    }

    loadDownloadIdToConfigMap()
    loadConfigMap()

    downloader = Downloader(reactContext)

    reactContext.addLifecycleEventListener(this);
  }

  override fun getName(): String {
    return NAME
  }

  // LifecycleEventListener methods
  override fun onHostResume() {
    val fetch = Fetch.Impl.getDefaultInstance()
    fetch.addListener(fetchListener)

    if (networkChangeReceiver == null) {
      networkChangeReceiver = NetworkChangeReceiver(this)
      val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
      reactApplicationContext.registerReceiver(networkChangeReceiver, filter)
      Log.d(name, "NetworkChangeReceiver re-registered in onHostResume")
    }

    fetch.getDownloads { downloads ->
      val list = downloads.toMutableList()
      list.sortBy { it.created }
      for (download in list) {
        val config = downloadIdToConfig[download.id.toLong()]
        if (config == null) {
          Log.d(name, "No config found for download ID: ${download.id}, canceling.")
          fetch.remove(download.id)
          continue
        }
        when (download.status) {
          Status.DOWNLOADING -> fetch.resume(download.id)
          Status.QUEUED -> {
            fetch.pause(download.id)
            fetch.resume(download.id)
          }
          Status.COMPLETED -> {
            val params: WritableMap = Arguments.createMap()
            params.putString("id", config.id)
            params.putString("location", download.file)
            params.putDouble("bytesDownloaded", download.downloaded.toDouble())
            params.putDouble("bytesTotal", download.total.toDouble())
            ee?.emit("downloadComplete", params)
          }
          else -> { /* no-op */ }
        }
      }
    }
  }

  override fun onHostPause() {
    Fetch.Impl.getDefaultInstance().removeListener(fetchListener)
  }

  override fun onHostDestroy() {
    Fetch.Impl.getDefaultInstance().close()

    networkChangeReceiver?.let {
      try {
        reactApplicationContext.unregisterReceiver(it)
        Log.d(name, "NetworkChangeReceiver unregistered")
      } catch (e: IllegalArgumentException) {
        Log.w(name, "Receiver already unregistered or not registered: ${e.message}")
      }
      networkChangeReceiver = null
    }
  }

  private val fetchListener = object : AbstractFetchListener() {
    override fun onAdded(download: Download) {
      val configId = getConfigIdFromDownload(download)

      val params = Arguments.createMap().apply {
        putString("id", configId)
        putLong("expectedBytes", download.total)
      }
      ee?.emit("downloadBegin", params)
    }

    override fun onQueued(download: Download, waitingOnNetwork: Boolean) {
      Log.d(name, "Download queued: ${download.id}")
    }

    override fun onCompleted(download: Download) {
      try {
        Log.d(name, "Download completed:${download.id}")
        val config = downloadIdToConfig[download.id.toLong()]

        val future = setFileChangesBeforeCompletion(config!!.tempFilePath!!, config.destination!!)
        future.get()

        val params = Arguments.createMap().apply {
          putString("id", config.id)
          putString("location", download.file)
          putLong("bytesDownloaded", download.downloaded)
          putLong("bytesTotal", download.total)
        }
        ee?.emit("downloadComplete", params)
      } catch (e: Exception) {
        onDownloadFailed(download)
        downloader.cancel(download.id.toLong())
      }
    }

    override fun onProgress(download: Download, etaInMilliseconds: Long, downloadedBytesPerSecond: Long) {
      onDownloadProgress(download)
    }

    override fun onPaused(download: Download) {
      // TODO
    }

    override fun onResumed(download: Download) {
      onDownloadProgress(download)
    }

    override fun onCancelled(download: Download) {
      val configId = getConfigIdFromDownload(download)

      val params = Arguments.createMap().apply {
        putString("id", configId)
        putLong("bytesDownloaded", download.downloaded)
        putLong("bytesTotal", download.total)
      }

      ee?.emit("downloadCancelled", params)
    }

    override fun onRemoved(download: Download) {
      // TODO
    }

    override fun onDeleted(download: Download) {
      // TODO
    }
  }

  override fun getConstants(): MutableMap<String?, Any?> {
    val context: Context = this.getReactApplicationContext()
    val constants: MutableMap<String?, Any?> = HashMap<String?, Any?>()

    val externalDirectory = context.getExternalFilesDir(null)
    if (externalDirectory != null) {
      constants.put("documents", externalDirectory.getAbsolutePath())
    } else {
      constants.put("documents", context.getFilesDir().getAbsolutePath())
    }

    constants.put("TaskRunning", TASK_RUNNING)
    constants.put("TaskSuspended", TASK_SUSPENDED)
    constants.put("TaskCanceling", TASK_CANCELING)
    constants.put("TaskCompleted", TASK_COMPLETED)


    // Expose storage type information for debugging/monitoring
    constants.put("isMMKVAvailable", isMMKVAvailable)
    constants.put("storageType", if (isMMKVAvailable) "MMKV" else "SharedPreferences")

    return constants
  }

  override fun initialize() {
    super.initialize()
    ee = getReactApplicationContext().getJSModule<DeviceEventManagerModule.RCTDeviceEventEmitter?>(
      DeviceEventManagerModule.RCTDeviceEventEmitter::class.java
    )

    val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
    networkChangeReceiver = NetworkChangeReceiver(this);
    getReactApplicationContext().registerReceiver(networkChangeReceiver, filter);
  }

  override fun invalidate() {

  }

  private fun getConfigIdFromDownload(download: Download): String? {
    val config = downloadIdToConfig[download.id.toLong()]
    Log.d(name, "getConfigIdFromDownload: ${download.identifier}, $config")
    return config?.id
  }

  private fun onDownloadFailed(download: Download) {
    val params = Arguments.createMap().apply {
      putString("id", download.id.toString())
      // TODO: can we support error code? Does it make sense?
      putInt("errorCode", -1)
      putString("error", download.error.toString())
    }
    ee?.emit("downloadFailed", params)
  }

  private fun onDownloadProgress(download: Download) {
    val configId = getConfigIdFromDownload(download)

    val params = Arguments.createMap().apply {
      putString("id", configId)
      putDouble("bytesDownloaded", download.downloaded.toDouble())
      putDouble("bytesTotal", download.total.toDouble())
    }

    val reportsArray = Arguments.createArray().apply {
      pushMap(params.copy())
    }

    ee?.emit("downloadProgress", reportsArray)
  }

  /**
   * Resolve redirects for a URL up to maxRedirects limit
   * @param originalUrl The original URL to follow
   * @param maxRedirects Maximum number of redirects to follow (0 means no redirect resolution)
   * @param headers Headers to include in redirect resolution requests
   * @return The final resolved URL, or original URL if maxRedirects is 0 or resolution fails
   */
  private fun resolveRedirects(
    originalUrl: String,
    maxRedirects: Int,
    headers: ReadableMap?
  ): String? {
    if (maxRedirects <= 0) {
      return originalUrl
    }

    try {
      var currentUrl: String? = originalUrl
      var redirectCount = 0

      while (redirectCount < maxRedirects) {
        val url = URL(currentUrl)
        val connection = url.openConnection() as HttpURLConnection


        // Add headers to the redirect resolution request
        if (headers != null) {
          val iterator = headers.keySetIterator()
          while (iterator.hasNextKey()) {
            val headerKey = iterator.nextKey()
            connection.setRequestProperty(headerKey, headers.getString(headerKey))
          }
        }


        // Add default headers for consistency with DownloadManager
        connection.setRequestProperty("Connection", "keep-alive")
        connection.setRequestProperty("Keep-Alive", "timeout=600, max=1000")
        if (!hasUserAgentHeader(headers)) {
          connection.setRequestProperty("User-Agent", "ReactNative-BackgroundDownloader/3.2.6")
        }

        connection.setInstanceFollowRedirects(false)
        connection.setRequestMethod("HEAD") // Use HEAD to avoid downloading content
        connection.setConnectTimeout(10000) // 10 second timeout
        connection.setReadTimeout(10000)

        val responseCode = connection.getResponseCode()

        if (responseCode >= 300 && responseCode < 400) {
          // This is a redirect
          var location = connection.getHeaderField("Location")
          if (location == null) {
            Log.w(getName(), "Redirect response without Location header at: " + currentUrl)
            break
          }


          // Handle relative URLs
          if (location.startsWith("/")) {
            val baseUrl = URL(currentUrl)
            location = baseUrl.getProtocol() + "://" + baseUrl.getHost() + location
          } else if (!location.startsWith("http")) {
            val baseUrl = URL(currentUrl)
            location = baseUrl.getProtocol() + "://" + baseUrl.getHost() + "/" + location
          }

          Log.d(
            getName(),
            "Redirect " + (redirectCount + 1) + "/" + maxRedirects + ": " + currentUrl + " -> " + location
          )
          currentUrl = location
          redirectCount++
        } else {
          // Not a redirect, we've found the final URL
          break
        }

        connection.disconnect()
      }

      if (redirectCount >= maxRedirects) {
        Log.w(
          getName(), "Reached maximum redirects (" + maxRedirects + ") for URL: " + originalUrl +
            ". Final URL: " + currentUrl
        )
      } else {
        Log.d(getName(), "Resolved URL after " + redirectCount + " redirects: " + currentUrl)
      }

      return currentUrl
    } catch (e: Exception) {
      Log.e(
        getName(),
        "Failed to resolve redirects for URL: " + originalUrl + ". Error: " + e.message
      )
      // Return original URL if redirect resolution fails
      return originalUrl
    }
  }

  @ReactMethod
  @Suppress("unused")
  fun download(options: ReadableMap) {
    val id = options.getString("id")
    var url = options.getString("url")
    val destination = options.getString("destination")
    val headers = options.getMap("headers")
    val metadata = options.getString("metadata")
    val notificationTitle = options.getString("notificationTitle")
    val progressIntervalScope = options.getInt("progressInterval")
    if (progressIntervalScope > 0) {
      progressInterval = progressIntervalScope
      saveConfigMap()
    }

    val progressMinBytesScope = options.getDouble("progressMinBytes")
    if (progressMinBytesScope > 0) {
      progressMinBytes = progressMinBytesScope.toLong()
      saveConfigMap()
    }

    val isAllowedOverRoaming = options.getBoolean("isAllowedOverRoaming")
    val isAllowedOverMetered = options.getBoolean("isAllowedOverMetered")
    val isNotificationVisible = options.getBoolean("isNotificationVisible")

    // Get maxRedirects parameter
    var maxRedirects = 0
    if (options.hasKey("maxRedirects")) {
      maxRedirects = options.getInt("maxRedirects")
    }

    if (id == null || url == null || destination == null) {
      Log.e(getName(), "download: id, url and destination must be set.")
      return
    }

    // Resolve redirects if maxRedirects is specified
    if (maxRedirects > 0) {
      Log.d(
        getName(),
        "Resolving redirects for URL: " + url + " (maxRedirects: " + maxRedirects + ")"
      )
      url = resolveRedirects(url, maxRedirects, headers)
      Log.d(getName(), "Final resolved URL: " + url)
    }

    val uuid = (System.currentTimeMillis() and 0xfffffffL).toInt()
    val extension = MimeTypeMap.getFileExtensionFromUrl(destination)
    val filename = "$uuid.$extension"

    val tempFile = File(reactApplicationContext.cacheDir, filename)
    val tempFilePath = tempFile.absolutePath

    val request = Request(url!!, tempFilePath)

    // Add default headers to improve connection handling for slow-responding URLs
    // These headers encourage longer connections and help prevent premature
    // timeouts
    request.addHeader("Connection", "keep-alive")
    request.addHeader("Keep-Alive", "timeout=600, max=1000")

    // Add a proper User-Agent to improve server compatibility
    if (!hasUserAgentHeader(headers)) {
      request.addHeader("User-Agent", "ReactNative-BackgroundDownloader/3.2.6")
    }

    if (headers != null) {
      val iterator = headers.keySetIterator()
      while (iterator.hasNextKey()) {
        val headerKey = iterator.nextKey()
          headers.getString(headerKey)?.let { request.addHeader(headerKey, it) }
      }
    }

    Log.d(
        name,
      "will download with requestId: " + request.id + ", id: " + id + ", url: " + url + ", file: " + tempFilePath
    )
    val requestId = request.id

    downloader.download(request)
    val config = RNBGDTaskConfig(id, url, destination, tempFilePath, null, null)

    synchronized(sharedLock) {
      Log.d(name, "will map configId: $id to downloadId: $requestId");
      configIdToDownloadId.put(id, requestId.toLong());
      downloadIdToConfig.put(requestId.toLong(), config);
      saveDownloadIdToConfigMap();
    }
  }

  // Pause functionality is not supported by Android DownloadManager.
  // This method will throw an UnsupportedOperationException to clearly indicate
  // that pause is not available on Android platform.
  @ReactMethod
  @Suppress("unused")
  fun pauseTask(configId: String?) {
    synchronized(sharedLock) {
      val downloadId: Long? = configIdToDownloadId[configId]
      if (downloadId != null) {
        try {
          downloader.pause(downloadId)
        } catch (e: java.lang.UnsupportedOperationException) {
          Log.w("RNBackgroundDownloader", "pauseTask: " + e.message)
          // Note: We don't rethrow the exception to avoid crashing the JS thread.
          // The limitation is already documented and expected.
        }
      }
    }
  }

  // Resume functionality is not supported by Android DownloadManager.
  // This method will throw an UnsupportedOperationException to clearly indicate
  // that resume is not available on Android platform.
  @ReactMethod
  @Suppress("unused")
  fun resumeTask(configId: String?) {
    synchronized(sharedLock) {
      val downloadId: Long? = configIdToDownloadId[configId]
      if (downloadId != null) {
        try {
          downloader.resume(downloadId)
        } catch (e: UnsupportedOperationException) {
          Log.w("RNBackgroundDownloader", "resumeTask: " + e.message)
          // Note: We don't rethrow the exception to avoid crashing the JS thread.
          // The limitation is already documented and expected.
        }
      }
    }
  }

  @ReactMethod
  @Suppress("unused")
  fun stopTask(configId: String?) {
    synchronized(sharedLock) {
      val downloadId: Long? = configIdToDownloadId[configId]
      if (downloadId != null) {
        Log.d(name, "will cancel download with id: $downloadId")
        downloader.cancel(downloadId)
      }
    }
  }

  @ReactMethod
  @Suppress("unused")
  fun completeHandler(configId: String?) {
    // Firebase Performance compatibility: Add defensive programming to prevent crashes
    // when Firebase Performance SDK is installed and uses bytecode instrumentation

    Log.d(getName(), "completeHandler called with configId: " + configId)

    // Defensive programming: Validate parameters
    if (configId == null || configId.isEmpty()) {
      Log.w(getName(), "completeHandler: Invalid configId provided")
      return
    }

    try {
      // Currently this method doesn't have any implementation on Android
      // as completion handlers are handled differently than iOS.
      // This defensive structure ensures Firebase Performance compatibility.
      Log.d(getName(), "completeHandler executed successfully for configId: " + configId)
    } catch (e: Exception) {
      // Catch any potential exceptions that might be thrown due to Firebase Performance
      // bytecode instrumentation interfering with method dispatch
      Log.e(getName(), "completeHandler: Exception occurred: " + Log.getStackTraceString(e))
    }
  }

  @ReactMethod
  @Suppress("unused")
  fun setApproval(isApproved: Boolean) {
    setIsApproved(isApproved)

    val cm = reactApplicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val activeNetwork = cm.activeNetwork
    val capabilities = cm.getNetworkCapabilities(activeNetwork)
    val isOnCellular = activeNetwork != null && capabilities != null && capabilities.hasTransport(
      NetworkCapabilities.TRANSPORT_CELLULAR)

    if (!isApproved && isOnCellular) {
      val fetch = Fetch.Impl.getDefaultInstance()
      fetch.getDownloads { downloads ->
        // Cancel all downloads in progress
        downloads.forEach { download ->
          if (download.status == Status.DOWNLOADING || download.status == Status.QUEUED) {
            fetch.cancel(download.id)
            fetch.delete(download.id)
          }
        }
      }
    }
  }

  @ReactMethod
  @Suppress("unused")
  fun getExistingDownloads(promise: Promise) {
    val foundTasks: WritableArray = Arguments.createArray()
    promise.resolve(foundTasks)
  }

  @ReactMethod
  @Suppress("unused")
  fun checkForExistingDownloads(promise: Promise) {
    val foundTasks: WritableArray = Arguments.createArray()

    val cm = reactApplicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val activeNetwork = cm.activeNetwork
    val capabilities = cm.getNetworkCapabilities(activeNetwork)
    val isOnWifi = activeNetwork != null && capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

    synchronized(sharedLock) {
      val fetch = Fetch.Impl.getDefaultInstance()
      fetch.getDownloads { downloads ->
        for (download in downloads) {
          val downloadId = download.id.toLong()
          if (downloadIdToConfig.containsKey(downloadId) && (getIsApproved() || isOnWifi)) {
            val config = downloadIdToConfig[downloadId]

            if (config != null) {
              Log.d(name, "check, id: ${downloadId}, config: ${config.id}, status: ${download.status}")

              val params: WritableMap = Arguments.createMap()

              params.putString("id", config.id)
              val status = stateMap[download.status.value] ?: 0
              params.putInt("state", status)
              val bytesDownloaded = download.downloaded
              params.putLong("bytesDownloaded", bytesDownloaded)
              val bytesTotal = download.total
              params.putLong("bytesTotal", bytesTotal)
              val percent = if (bytesTotal > 0) bytesDownloaded / bytesTotal else 0.0

              foundTasks.pushMap(params)
              configIdToDownloadId[config.id] = downloadId
            }
          } else {
            downloader.cancel(downloadId)
          }
        }

        promise.resolve(foundTasks)
      }
    }
  }

  @ReactMethod
  @Suppress("unused")
  fun addListener(eventName: String?) {
  }

  @ReactMethod
  @Suppress("unused")
  fun removeListeners(count: Int?) {
  }

  private fun saveDownloadIdToConfigMap() {
    synchronized(sharedLock) {
      try {
        val gson: Gson = Gson()
        val str: String? = gson.toJson(downloadIdToConfig)

        if (isMMKVAvailable && mmkv != null) {
          mmkv!!.encode(getName() + "_downloadIdToConfig", str)
          Log.d(getName(), "Saved download config to MMKV")
        } else if (sharedPreferences != null) {
          sharedPreferences!!.edit {
            putString(getName() + "_downloadIdToConfig", str)
          }
          Log.d(getName(), "Saved download config to SharedPreferences fallback")
        } else {
          Log.w(getName(), "No storage available, skipping download config persistence")
        }
      } catch (e: Exception) {
        Log.e(getName(), "Failed to save download config: " + e.message)
      }
    }
  }

  private fun loadDownloadIdToConfigMap() {
    synchronized(sharedLock) {
      downloadIdToConfig = HashMap()
      try {
        var str: String? = null

        if (isMMKVAvailable && mmkv != null) {
          str = mmkv!!.decodeString(getName() + "_downloadIdToConfig")
          if (str != null) {
            Log.d(getName(), "Loaded download config from MMKV")
          }
        } else if (sharedPreferences != null) {
          str = sharedPreferences!!.getString(getName() + "_downloadIdToConfig", null)
          if (str != null) {
            Log.d(getName(), "Loaded download config from SharedPreferences fallback")
          }
        }

        if (str != null) {
          val gson: Gson = Gson()
          val mapType: TypeToken<MutableMap<Long?, RNBGDTaskConfig?>?> =
            object : TypeToken<MutableMap<Long?, RNBGDTaskConfig?>?>() {}
          downloadIdToConfig = gson.fromJson(str, mapType)!! as MutableMap<Long, RNBGDTaskConfig?>
        } else {
          Log.d(getName(), "No existing download config found, starting with empty map")
        }
      } catch (e: Exception) {
        Log.e(getName(), "Failed to load download config: " + e.message)
        downloadIdToConfig = HashMap()
      }
    }
  }

  private fun saveConfigMap() {
    synchronized(sharedLock) {
      try {
        if (isMMKVAvailable && mmkv != null) {
          mmkv!!.encode(getName() + "_progressInterval", progressInterval)
          mmkv!!.encode(getName() + "_progressMinBytes", progressMinBytes)
          Log.d(getName(), "Saved config to MMKV")
        } else if (sharedPreferences != null) {
          sharedPreferences!!.edit {
            putInt(getName() + "_progressInterval", progressInterval)
              .putLong(getName() + "_progressMinBytes", progressMinBytes)
          }
          Log.d(getName(), "Saved config to SharedPreferences fallback")
        } else {
          Log.w(getName(), "No storage available, skipping config persistence")
        }
      } catch (e: Exception) {
        Log.e(getName(), "Failed to save config: " + e.message)
      }
    }
  }

  private fun loadConfigMap() {
    synchronized(sharedLock) {
      try {
        if (isMMKVAvailable && mmkv != null) {
          val progressIntervalScope: Int = mmkv!!.decodeInt(getName() + "_progressInterval")
          if (progressIntervalScope > 0) {
            progressInterval = progressIntervalScope
          }
          val progressMinBytesScope: Long = mmkv!!.decodeLong(getName() + "_progressMinBytes")
          if (progressMinBytesScope > 0) {
            progressMinBytes = progressMinBytesScope
          }
          Log.d(getName(), "Loaded config from MMKV")
        } else if (sharedPreferences != null) {
          val progressIntervalScope = sharedPreferences!!.getInt(getName() + "_progressInterval", 0)
          if (progressIntervalScope > 0) {
            progressInterval = progressIntervalScope
          }
          val progressMinBytesScope = sharedPreferences!!.getLong(getName() + "_progressMinBytes", 0)
          if (progressMinBytesScope > 0) {
            progressMinBytes = progressMinBytesScope
          }
          Log.d(getName(), "Loaded config from SharedPreferences fallback")
        } else {
          Log.d(getName(), "No storage available, using default config values")
        }
      } catch (e: Exception) {
        Log.e(getName(), "Failed to load config: " + e.message)
      }
    }
  }

  private fun setFileChangesBeforeCompletion(
    targetSrc: String,
    destinationSrc: String
  ): Future<Boolean?> {
    return fixedExecutorPool.submit<Boolean?>(Callable {
      val file = File(targetSrc)
      val destination = File(destinationSrc)
      var destinationParent: File? = null
      try {
        if (file.exists()) {
          FileUtils.rm(destination)
          destinationParent = FileUtils.mkdirParent(destination)
          FileUtils.mv(file, destination)
        }
      } catch (e: IOException) {
        FileUtils.rm(file)
        FileUtils.rm(destination)
        FileUtils.rm(destinationParent)
        throw Exception(e)
      }
      true
    })
  }

  /**
   * Check if the provided headers already contain a User-Agent header
   * (case-insensitive)
   */
  private fun hasUserAgentHeader(headers: ReadableMap?): Boolean {
    if (headers == null) {
      return false
    }

    val iterator = headers.keySetIterator()
    while (iterator.hasNextKey()) {
      val headerKey = iterator.nextKey()
      if (headerKey != null && headerKey.lowercase(Locale.getDefault()) == "user-agent") {
        return true
      }
    }

    return false
  }

  fun getIsApproved(): Boolean {
    val prefs: SharedPreferences = reactApplicationContext.getSharedPreferences("RNBDPrefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("isApproved", false)
  }

  private fun setIsApproved(isApproved: Boolean) {
    val prefs: SharedPreferences = reactApplicationContext.getSharedPreferences("RNBDPrefs", Context.MODE_PRIVATE)
    prefs.edit { putBoolean("isApproved", isApproved) }
  }

  companion object {
    const val NAME: String = "RNBackgroundDownloader"

    private const val TASK_RUNNING = 0
    private const val TASK_SUSPENDED = 1
    private const val TASK_CANCELING = 2
    private const val TASK_COMPLETED = 3

    private const val ERR_STORAGE_FULL = 0
    private const val ERR_NO_INTERNET = 1
    private const val ERR_NO_WRITE_PERMISSION = 2
    private const val ERR_FILE_NOT_FOUND = 3
    private const val ERR_OTHERS = 100
    private val stateMap: MutableMap<Int?, Int?> = object : HashMap<Int?, Int?>() {
      init {
        put(DownloadManager.STATUS_FAILED, TASK_CANCELING)
        put(DownloadManager.STATUS_PAUSED, TASK_SUSPENDED)
        put(DownloadManager.STATUS_PENDING, TASK_RUNNING)
        put(DownloadManager.STATUS_RUNNING, TASK_RUNNING)
        put(DownloadManager.STATUS_SUCCESSFUL, TASK_COMPLETED)
      }
    }

    private var mmkv: MMKV? = null
    private var sharedPreferences: SharedPreferences? = null
    private var isMMKVAvailable = false
    private val sharedLock = Any()
  }
}
