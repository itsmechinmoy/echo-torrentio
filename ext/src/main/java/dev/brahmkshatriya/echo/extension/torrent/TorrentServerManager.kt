package dev.brahmkshatriya.echo.extension.torrent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class TorrentStreamMeta(
    val infoHash: String,
    val fileIdx: Int,
    val magnetUrl: String,
    val title: String
)

object TorrentServerManager {
    var logger: (String) -> Unit = { println("[TorrentServerManager] $it") }

    private val sessionManager: SessionManager? by lazy {
        initNativeLibrary()
        try {
            SessionManager()
        } catch (t: Throwable) {
            logger("Native libtorrent4j not available: ${t.message}")
            null
        }
    }

    private var httpServer: TorrentHttpServer? = null
    var activeTorrentHash: String? = null
    var serverPort: Int = 8090
        private set

    // Stream metadata registry: maps "${infoHash}_${fileIdx}" to metadata
    private val registeredStreams = ConcurrentHashMap<String, TorrentStreamMeta>()

    // Active connection tracking & idle auto-pause watchdog
    private val activeConnections = AtomicInteger(0)
    private var idlePauseJob: Job? = null
    private val watchdogScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://tracker.coppersurfer.tk:6969/announce",
        "http://tracker.openbittorrent.com:80/announce",
        "udp://opentracker.i2p.rocks:6969/announce",
        "udp://tracker.internetwarriors.net:1337/announce",
        "udp://tracker.openbts.com:6969/announce",
        "http://nyaa.tracker.wf:7777/announce",
        "http://anidex.moe:6969/announce",
        "http://tracker.anirena.com:80/announce",
        "udp://tracker.uw0.xyz:6969/announce",
        "udp://47.ip-51-68-199.eu:6969/announce"
    )

    const val DHT_BOOTSTRAP_NODES =
        "dht.libtorrent.org:25401,dht.transmissionbt.com:6881,router.bittorrent.com:6881,router.utorrent.com:6881,router.bt.ouinet.work:6881"

    @Synchronized
    fun start() {
        if (httpServer != null) return
        logger("Starting built-in TorrentServerManager...")
        try {
            val sm = sessionManager
            if (sm != null) {
                try {
                    val settings = SettingsPack()

                    // 1. Discovery & Protocols
                    settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_upnp.swigValue(), true)
                    settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_natpmp.swigValue(), true)
                    settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_lsd.swigValue(), true)
                    settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_dht.swigValue(), true)
                    settings.setString(org.libtorrent4j.swig.settings_pack.string_types.dht_bootstrap_nodes.swigValue(), DHT_BOOTSTRAP_NODES)

                    // Announce to all trackers across all tiers simultaneously
                    settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
                    settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)

                    // 2. Mobile-Optimized Disk I/O & OS RAM Cache
                    settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.max_queued_disk_bytes.swigValue(), 16 * 1024 * 1024)
                    settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.aio_threads.swigValue(), 4)
                    settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.file_pool_size.swigValue(), 50)
                    settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.checking_mem_usage.swigValue(), 1024)

                    // Connection pacing for rapid swarm acquisition & streaming
                    settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.connection_speed.swigValue(), 40)
                    settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.peer_turnover.swigValue(), 4)
                    settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.peer_turnover_cutoff.swigValue(), 90)
                    settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.peer_turnover_interval.swigValue(), 60)
                    settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.max_out_request_queue.swigValue(), 1000)
                    settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.max_allowed_in_request_queue.swigValue(), 2000)
                    settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.unchoke_slots_limit.swigValue(), 12)

                    // Listen interfaces
                    settings.setString(org.libtorrent4j.swig.settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:0,[::]:0")

                    val params = SessionParams(settings)
                    sm.start(params)
                    sm.startDht()
                } catch (t: Throwable) {
                    logger("SessionManager start warning: ${t.message}")
                }
            }

            serverPort = findFreePort(8090)
            httpServer = TorrentHttpServer(serverPort, { hash, fileIndex ->
                prepareAndGetHandle(hash, fileIndex)
            }, {
                getTorrentCacheDir().absolutePath
            }, logger)
            httpServer?.start()
            logger("TorrentServerManager started. Port: $serverPort")
        } catch (e: Exception) {
            logger("Failed to start TorrentServerManager: ${e.message}")
            e.printStackTrace()
        }
    }

    @Synchronized
    fun stop() {
        logger("Stopping TorrentServerManager...")
        synchronized(this) {
            idlePauseJob?.cancel()
            idlePauseJob = null
        }
        httpServer?.stop()
        httpServer = null
        try {
            val sm = sessionManager
            if (sm != null && sm.isRunning) {
                sm.stop()
            }
        } catch (_: Throwable) {}
    }

    fun isRunning(): Boolean = (sessionManager?.isRunning == true) || (httpServer != null)

    fun registerStream(infoHash: String, fileIdx: Int, magnetUrl: String, title: String) {
        val key = "${infoHash.lowercase()}_$fileIdx"
        registeredStreams[key] = TorrentStreamMeta(infoHash, fileIdx, magnetUrl, title)
    }

    fun getStreamUrl(torrentHash: String, fileIndex: Int, preferredName: String = "video.mkv"): String {
        start()
        val cleanName = preferredName.substringAfterLast("/").substringAfterLast("\\").ifBlank { "video.mkv" }
        val encodedName = runCatching { URLEncoder.encode(cleanName, "UTF-8") }.getOrDefault("video.mkv")
        return "http://127.0.0.1:$serverPort/stream/$encodedName?hash=$torrentHash&index=$fileIndex"
    }

    fun getLink(torrentHash: String, fileIndex: Int): String = getStreamUrl(torrentHash, fileIndex)

    fun onStreamClientConnected() {
        val active = activeConnections.incrementAndGet()
        logger("Stream client connected. Active connections: $active")
        synchronized(this) {
            idlePauseJob?.cancel()
            idlePauseJob = null
        }
        resumeActiveTorrent()
    }

    fun onStreamClientDisconnected() {
        val active = activeConnections.decrementAndGet()
        logger("Stream client disconnected. Active connections: $active")
        if (active <= 0) {
            synchronized(this) {
                idlePauseJob?.cancel()
                idlePauseJob = watchdogScope.launch {
                    delay(12_000L) // 12-second grace period for seek or track change
                    if (activeConnections.get() <= 0) {
                        logger("No active streaming clients for 12s. Pausing active torrent engine to save battery & data.")
                        pauseActiveTorrent()
                    }
                }
            }
        }
    }

    @Synchronized
    fun pauseActiveTorrent() {
        try {
            val sm = sessionManager ?: return
            val hash = activeTorrentHash ?: return
            val sha1 = Sha1Hash.parseHex(hash)
            val handle = sm.find(sha1)
            if (handle != null && handle.isValid) {
                logger("Pausing active torrent: $hash")
                handle.pause()
            }
        } catch (e: Exception) {
            logger("Failed to pause torrent: ${e.message}")
        }
    }

    @Synchronized
    fun resumeActiveTorrent() {
        try {
            val sm = sessionManager ?: return
            val hash = activeTorrentHash ?: return
            val sha1 = Sha1Hash.parseHex(hash)
            val handle = sm.find(sha1)
            if (handle != null && handle.isValid) {
                logger("Resuming active torrent: $hash")
                handle.resume()
            }
        } catch (e: Exception) {
            logger("Failed to resume torrent: ${e.message}")
        }
    }

    /**
     * Lazily activates the requested torrent on-demand when ExoPlayer opens the stream.
     * Pauses any previously running torrent so only 1 torrent runs at any given time.
     */
    @Synchronized
    fun prepareAndGetHandle(hash: String, fileIndex: Int): TorrentHandle? {
        start()
        val sm = sessionManager ?: run {
            logger("SessionManager not loaded, skipping p2p torrent download")
            return null
        }

        val sha1 = try {
            Sha1Hash.parseHex(hash)
        } catch (_: Exception) {
            return null
        }

        // If user switched to another torrent or episode, pause the previous one
        if (activeTorrentHash != null && !activeTorrentHash.equals(hash, ignoreCase = true)) {
            logger("Switching active torrent from $activeTorrentHash to $hash: pausing previous torrent")
            pauseActiveTorrent()
        }

        var handle = sm.find(sha1)
        if (handle == null || !handle.isValid) {
            val meta = registeredStreams["${hash.lowercase()}_$fileIndex"]
                ?: registeredStreams.values.firstOrNull { it.infoHash.equals(hash, ignoreCase = true) }
            val rawMagnet = meta?.magnetUrl ?: "magnet:?xt=urn:btih:$hash"
            val enhancedUrl = enhanceMagnetUrl(rawMagnet)

            pruneCache() // Keep temporary cache bounded (LRU)

            logger("Initiating on-demand sequential download for torrent: $hash")
            val cacheDir = getTorrentCacheDir()
            sm.download(enhancedUrl, cacheDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            handle = sm.find(sha1)
        }

        activeTorrentHash = hash

        if (handle != null && handle.isValid) {
            handle.resume()

            // Wait for metadata (up to 30 seconds)
            var waitTime = 0
            while (handle.torrentFile() == null && waitTime < 300) {
                Thread.sleep(100)
                waitTime++
                handle = sm.find(sha1) ?: handle
            }

            val torrentInfo = handle.torrentFile()
            if (torrentInfo != null) {
                val numFiles = torrentInfo.numFiles()
                if (fileIndex in 0 until numFiles) {
                    val priorities = Array(numFiles) { Priority.IGNORE }
                    priorities[fileIndex] = Priority.TOP_PRIORITY
                    handle.prioritizeFiles(priorities)

                    // Trigger 1% head and 1% tail piece deadlines
                    setupPrebufferPieces(handle, torrentInfo, fileIndex)
                }
            }
        }

        return handle
    }

    private fun setupPrebufferPieces(handle: TorrentHandle, torrentInfo: TorrentInfo, fileIndex: Int) {
        try {
            val fileStorage = torrentInfo.files()
            val fileOffset = fileStorage.fileOffset(fileIndex)
            val fileSize = fileStorage.fileSize(fileIndex)
            val pieceLength = torrentInfo.pieceLength().toLong()
            val numPiecesTotal = torrentInfo.numPieces()

            val firstPiece = (fileOffset / pieceLength).toInt()
            val lastPiece = if (fileSize > 0) ((fileOffset + fileSize - 1) / pieceLength).toInt() else firstPiece

            val numPiecesOnePercent = if (fileSize > 0 && pieceLength > 0) {
                ((fileSize * 0.01) / pieceLength).toInt().coerceIn(2, 16)
            } else 2

            logger("Setting piece deadlines for file $fileIndex: $numPiecesOnePercent head pieces, $numPiecesOnePercent tail pieces")

            // 1. Prioritize Head Pieces (Container Headers & Initial Video Chunks)
            for (i in 0 until numPiecesOnePercent) {
                val p = firstPiece + i
                if (p <= lastPiece && p < numPiecesTotal) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, i * 200)
                }
            }

            // 2. Prioritize Tail Pieces (moov atom / Matroska Cues)
            for (i in 0 until numPiecesOnePercent) {
                val p = lastPiece - i
                if (p >= firstPiece && p < numPiecesTotal) {
                    handle.piecePriority(p, Priority.DEFAULT)
                    handle.setPieceDeadline(p, 2000 + (i * 400))
                }
            }
        } catch (e: Exception) {
            logger("Failed to setup piece deadlines: ${e.message}")
        }
    }

    fun enhanceMagnetUrl(url: String): String {
        if (!url.startsWith("magnet:", ignoreCase = true)) return url
        val builder = StringBuilder(url)
        for (tracker in DEFAULT_TRACKERS) {
            val encoded = runCatching { URLEncoder.encode(tracker, "UTF-8") }.getOrDefault(tracker)
            if (!url.contains(encoded) && !url.contains(tracker)) {
                builder.append("&tr=").append(encoded)
            }
        }
        return builder.toString()
    }

    fun addTorrent(
        url: String,
        title: String = ""
    ): TorrentHandle? {
        start()

        val sm = sessionManager ?: run {
            logger("SessionManager not loaded, skipping p2p torrent download")
            return null
        }

        val cacheDir = getTorrentCacheDir()
        var handle: TorrentHandle? = null

        if (url.startsWith("magnet:", ignoreCase = true)) {
            val enhancedUrl = enhanceMagnetUrl(url)
            sm.download(enhancedUrl, cacheDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            val infoHash = parseMagnetHash(url)
            val sha1 = Sha1Hash.parseHex(infoHash)
            handle = sm.find(sha1)

            // Wait for metadata (up to 30 seconds)
            var waitTime = 0
            while ((handle == null || handle.torrentFile() == null) && waitTime < 300) {
                Thread.sleep(100)
                handle = sm.find(sha1)
                waitTime++
            }
            if (handle != null && handle.torrentFile() != null) {
                val numFiles = handle.torrentFile()!!.numFiles()
                val priorities = Priority.array(Priority.IGNORE, numFiles)
                handle.prioritizeFiles(priorities)
            }
        } else if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            val tempFile = downloadTorrentFile(url)
            if (tempFile != null) {
                val ti = TorrentInfo(tempFile)
                val p = Priority.array(Priority.IGNORE, ti.numFiles())
                sm.download(ti, cacheDir, null, p, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                handle = sm.find(ti.infoHash())
            }
        } else {
            val path = url.removePrefix("file://")
            val file = File(path)
            if (file.exists()) {
                val ti = TorrentInfo(file)
                val p = Priority.array(Priority.IGNORE, ti.numFiles())
                sm.download(ti, cacheDir, null, p, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                handle = sm.find(ti.infoHash())
            }
        }

        if (handle != null) {
            handle.resume()
            activeTorrentHash = handle.infoHash().toHex()
        }
        return handle
    }

    fun prebuffer(torrentHash: String, fileIndex: Int): Boolean {
        try {
            val sm = sessionManager ?: return false
            val sha1 = Sha1Hash.parseHex(torrentHash)
            val handle = sm.find(sha1) ?: return false

            var waitTime = 0
            while (handle.torrentFile() == null && waitTime < 200) {
                Thread.sleep(100)
                waitTime++
            }

            val torrentInfo = handle.torrentFile() ?: return false
            val fileStorage = torrentInfo.files()

            if (fileIndex < 0 || fileIndex >= fileStorage.numFiles()) return false
            handle.filePriority(fileIndex, Priority.TOP_PRIORITY)

            val fileOffset = fileStorage.fileOffset(fileIndex)
            val fileSize = fileStorage.fileSize(fileIndex)
            val pieceLength = torrentInfo.pieceLength().toLong()
            val numPiecesTotal = torrentInfo.numPieces()

            val firstPiece = (fileOffset / pieceLength).toInt()
            val lastPiece = if (fileSize > 0) ((fileOffset + fileSize - 1) / pieceLength).toInt() else firstPiece

            val numPiecesOnePercent = if (fileSize > 0 && pieceLength > 0) {
                ((fileSize * 0.01) / pieceLength).toInt().coerceIn(2, 16)
            } else 2

            logger("Pre-buffering $numPiecesOnePercent head pieces and $numPiecesOnePercent tail pieces for file $fileIndex")

            // 1. Prioritize Head Pieces (Container Headers & Video Start)
            for (i in 0 until numPiecesOnePercent) {
                val p = firstPiece + i
                if (p <= lastPiece && p < numPiecesTotal) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, i * 250)
                }
            }

            // 2. Prioritize Tail Pieces (moov atom / Matroska Cues)
            for (i in 0 until numPiecesOnePercent) {
                val p = lastPiece - i
                if (p >= firstPiece && p < numPiecesTotal) {
                    handle.piecePriority(p, Priority.DEFAULT)
                    handle.setPieceDeadline(p, 3000 + (i * 500))
                }
            }

            // 3. Quick check for initial readiness
            var waitCount = 0
            while (!handle.havePiece(firstPiece) && waitCount < 60) {
                if (!sm.isRunning || !handle.isValid) break
                Thread.sleep(100)
                waitCount++
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun removeTorrent(torrentHash: String) {
        try {
            val sm = sessionManager ?: return
            val sha1 = Sha1Hash.parseHex(torrentHash)
            val handle = sm.find(sha1)
            if (handle != null && handle.isValid) {
                sm.remove(handle)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getTorrentCacheDir(): File {
        val appContext = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThread.getMethod("currentApplication")
            currentApplication.invoke(null)
        }.getOrNull()

        val cacheBase: File = if (appContext != null) {
            runCatching {
                val getCacheDir = appContext.javaClass.getMethod("getCacheDir")
                getCacheDir.invoke(appContext) as? File
            }.getOrNull() ?: File(System.getProperty("java.io.tmpdir", "."))
        } else {
            File(System.getProperty("java.io.tmpdir", "."))
        }

        val dir = File(cacheBase, "torrent_cache")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun findFreePort(startPort: Int): Int {
        var port = startPort
        while (port < 65535) {
            try {
                ServerSocket(port).use {
                    return port
                }
            } catch (_: Exception) {
                port++
            }
        }
        return startPort
    }

    private fun parseMagnetHash(url: String): String {
        val xtIndex = url.indexOf("xt=urn:btih:", ignoreCase = true)
        if (xtIndex != -1) {
            var hash = url.substring(xtIndex + 12)
            val ampersandIndex = hash.indexOf("&")
            if (ampersandIndex != -1) {
                hash = hash.substring(0, ampersandIndex)
            }
            return hash.uppercase()
        }
        throw IllegalArgumentException("Invalid magnet link: $url")
    }

    private fun downloadTorrentFile(url: String): File? {
        try {
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes() ?: return null
                    val tempFile = File.createTempFile("temp", ".torrent", getTorrentCacheDir())
                    tempFile.writeBytes(bytes)
                    return tempFile
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun pruneCache(maxSizeBytes: Long = 3L * 1024L * 1024L * 1024L) {
        try {
            val cacheDir = getTorrentCacheDir()
            val files = cacheDir.listFiles() ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize > maxSizeBytes) {
                val sortedFiles = files.sortedBy { it.lastModified() }
                for (file in sortedFiles) {
                    if (totalSize <= maxSizeBytes) break
                    val size = file.length()
                    if (file.deleteRecursively()) {
                        totalSize -= size
                        logger("Pruned old torrent cache file ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Resolves the native libtorrent4j library (.so) on Android:
     * 1. Checks Echo's extracted library directory (context.cacheDir/libs/torrentio)
     * 2. Checks nativeLibraryDir of the host app
     * 3. Extracts directly from the extension APK if needed
     * 4. Sets System property "libtorrent4j.jni.path" and invokes System.load()
     */
    fun initNativeLibrary() {
        if (System.getProperty("libtorrent4j.jni.path") != null) return

        val appContext = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThread.getMethod("currentApplication")
            currentApplication.invoke(null)
        }.getOrNull() ?: return

        try {
            val getCacheDir = appContext.javaClass.getMethod("getCacheDir")
            val cacheDir = getCacheDir.invoke(appContext) as? File

            val candidateDirs = mutableListOf<File>()
            if (cacheDir != null) {
                candidateDirs.add(File(cacheDir, "libs/torrentio"))
                candidateDirs.add(File(cacheDir, "libs"))
            }

            runCatching {
                val getAppInfo = appContext.javaClass.getMethod("getApplicationInfo")
                val appInfo = getAppInfo.invoke(appContext)
                val nativeLibDirField = appInfo.javaClass.getField("nativeLibraryDir")
                val nativeLibDirPath = nativeLibDirField.get(appInfo) as? String
                if (!nativeLibDirPath.isNullOrBlank()) {
                    candidateDirs.add(File(nativeLibDirPath))
                }
            }

            for (dir in candidateDirs) {
                val soFile = File(dir, "libtorrent4j.so")
                if (soFile.exists() && soFile.length() > 0) {
                    System.setProperty("libtorrent4j.jni.path", soFile.absolutePath)
                    runCatching { System.load(soFile.absolutePath) }
                    logger("Loaded native libtorrent4j from: ${soFile.absolutePath}")
                    return
                }
            }

            // Fallback: extract directly from the extension's installed APK
            if (cacheDir != null) {
                val targetDir = File(cacheDir, "libs/torrentio").apply { mkdirs() }
                val targetSo = File(targetDir, "libtorrent4j.so")

                val getPackageManager = appContext.javaClass.getMethod("getPackageManager")
                val pm = getPackageManager.invoke(appContext)
                val getPackageInfo = pm.javaClass.getMethod("getPackageInfo", String::class.java, Int::class.javaPrimitiveType)
                val pkgInfo = runCatching {
                    getPackageInfo.invoke(pm, "dev.brahmkshatriya.echo.extension.torrentio", 0)
                }.getOrNull()

                if (pkgInfo != null) {
                    val appInfoField = pkgInfo.javaClass.getField("applicationInfo")
                    val appInfo = appInfoField.get(pkgInfo)
                    val sourceDirField = appInfo.javaClass.getField("sourceDir")
                    val apkPath = sourceDirField.get(appInfo) as? String

                    if (apkPath != null && File(apkPath).exists()) {
                        val zip = java.util.zip.ZipFile(apkPath)
                        val supportedAbis = try {
                            val buildClass = Class.forName("android.os.Build")
                            val abisField = buildClass.getField("SUPPORTED_ABIS")
                            @Suppress("UNCHECKED_CAST")
                            abisField.get(null) as? Array<String>
                        } catch (_: Exception) { null } ?: arrayOf("arm64-v8a", "armeabi-v7a")

                        var entry: java.util.zip.ZipEntry? = null
                        for (abi in supportedAbis) {
                            entry = zip.getEntry("lib/$abi/libtorrent4j.so")
                            if (entry != null) break
                        }

                        if (entry != null) {
                            zip.getInputStream(entry).use { input ->
                                targetSo.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            zip.close()
                            System.setProperty("libtorrent4j.jni.path", targetSo.absolutePath)
                            runCatching { System.load(targetSo.absolutePath) }
                            logger("Extracted and loaded native libtorrent4j from APK to: ${targetSo.absolutePath}")
                            return
                        }
                        zip.close()
                    }
                }
            }
        } catch (t: Throwable) {
            logger("initNativeLibrary warning: ${t.message}")
        }
    }
}
