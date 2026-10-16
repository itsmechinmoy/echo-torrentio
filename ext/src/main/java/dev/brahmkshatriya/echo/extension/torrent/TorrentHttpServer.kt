package dev.brahmkshatriya.echo.extension.torrent

import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TorrentHttpServer(
    private val port: Int,
    private val getTorrentHandle: (String, Int) -> TorrentHandle?,
    private val getSavePath: () -> String,
    private val logger: (String) -> Unit = { println("[TorrentHttpServer] $it") }
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newFixedThreadPool(4)
    private val activeStreamCancelFlag = ConcurrentHashMap<String, AtomicBoolean>()
    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        try {
            serverSocket = ServerSocket(port)
            executor.submit {
                while (isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        executor.submit { handleClient(socket) }
                    } catch (_: Exception) {
                        // Socket closed or error
                    }
                }
            }
            logger("Started listening on port $port")
        } catch (e: Exception) {
            logger("Failed to start server: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        activeStreamCancelFlag.values.forEach { it.set(true) }
        activeStreamCancelFlag.clear()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        executor.shutdownNow()
        logger("Stopped")
    }

    private fun handleClient(socket: Socket) {
        var isStreamActive = false
        socket.use { client ->
            try {
                client.tcpNoDelay = true
                val input = client.getInputStream()
                val reader = input.bufferedReader()
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val uri = parts[1]

                if (method != "GET" && method != "HEAD") {
                    sendError(client.getOutputStream(), 405, "Method Not Allowed")
                    return
                }

                val queryStartIndex = uri.indexOf('?')
                if (queryStartIndex == -1) {
                    sendError(client.getOutputStream(), 400, "Bad Request")
                    return
                }
                val path = uri.substring(0, queryStartIndex)
                if (!path.startsWith("/stream")) {
                    sendError(client.getOutputStream(), 404, "Not Found")
                    return
                }
                val query = uri.substring(queryStartIndex + 1)
                val params = query.split("&").associate {
                    val pair = it.split("=")
                    val key = pair.getOrNull(0) ?: ""
                    val value = if (pair.size > 1) URLDecoder.decode(pair[1], "UTF-8") else ""
                    key to value
                }

                val hash = params["hash"] ?: params["link"] ?: ""
                val indexStr = params["index"] ?: ""
                if (hash.isEmpty() || indexStr.isEmpty()) {
                    sendError(client.getOutputStream(), 400, "Missing parameters")
                    return
                }

                val fileIndex = indexStr.toIntOrNull() ?: 0

                val streamKey = "${hash.lowercase()}_$fileIndex"
                val oldCancel = activeStreamCancelFlag.put(streamKey, AtomicBoolean(false))
                oldCancel?.set(true)
                val myCancel = activeStreamCancelFlag[streamKey] ?: AtomicBoolean(false)

                // Track active stream connection
                TorrentServerManager.onStreamClientConnected()
                isStreamActive = true

                // On-demand activation & handle retrieval
                val torrentHandle = getTorrentHandle(hash, fileIndex)
                if (torrentHandle == null || !torrentHandle.isValid) {
                    sendError(client.getOutputStream(), 404, "Torrent not found or metadata timed out")
                    return
                }

                var torrentInfo = torrentHandle.torrentFile()
                if (torrentInfo == null) {
                    var waitMetadata = 0
                    while (torrentHandle.torrentFile() == null && waitMetadata < 300) {
                        if (myCancel.get() || !isRunning || !torrentHandle.isValid || client.isClosed) break
                        Thread.sleep(100)
                        waitMetadata++
                    }
                    torrentInfo = torrentHandle.torrentFile()
                }

                if (torrentInfo == null) {
                    sendError(client.getOutputStream(), 504, "Metadata timeout")
                    return
                }

                val fileStorage = torrentInfo.files()
                if (fileIndex < 0 || fileIndex >= fileStorage.numFiles()) {
                    sendError(client.getOutputStream(), 400, "Invalid file index")
                    return
                }

                // Prioritize this file's pieces
                val priorities = Array(fileStorage.numFiles()) { Priority.IGNORE }
                priorities[fileIndex] = Priority.TOP_PRIORITY
                torrentHandle.prioritizeFiles(priorities)

                val fileSize = fileStorage.fileSize(fileIndex)
                val fileOffset = fileStorage.fileOffset(fileIndex)
                val pieceLength = torrentInfo.pieceLength().toLong()
                val totalPieces = torrentInfo.numPieces()

                val firstPiece = (fileOffset / pieceLength).toInt()
                val lastPiece = if (fileSize > 0) ((fileOffset + fileSize - 1) / pieceLength).toInt() else firstPiece
                val numPiecesOnePercent = if (fileSize > 0 && pieceLength > 0) {
                    ((fileSize * 0.01) / pieceLength).toInt().coerceIn(2, 16)
                } else 2
                val headEndPiece = firstPiece + numPiecesOnePercent
                val tailStartPiece = lastPiece - numPiecesOnePercent + 1

                // Read headers to check for HTTP Range
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substring(6).trim()
                    }
                }

                var startByte = 0L
                var endByte = fileSize - 1

                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val rangeParts = rangeHeader.substring(6).split("-")
                    val startStr = rangeParts.getOrNull(0)?.trim() ?: ""
                    val endStr = rangeParts.getOrNull(1)?.trim() ?: ""

                    if (startStr.isNotEmpty()) {
                        startByte = startStr.toLong()
                    }
                    if (endStr.isNotEmpty()) {
                        endByte = endStr.toLong()
                    }
                }

                if (startByte < 0 || startByte >= fileSize || endByte < startByte || endByte >= fileSize) {
                    sendError(client.getOutputStream(), 416, "Requested Range Not Satisfiable")
                    return
                }

                val contentSize = endByte - startByte + 1
                val output = client.getOutputStream()

                // Send response headers
                val contentType = getMimeType(fileStorage.filePath(fileIndex))
                val responseHeaders = if (rangeHeader != null) {
                    "HTTP/1.1 206 Partial Content\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            "Content-Type: $contentType\r\n" +
                            "Content-Length: $contentSize\r\n" +
                            "Content-Range: bytes $startByte-$endByte/$fileSize\r\n" +
                            "Connection: keep-alive\r\n" +
                            "Access-Control-Allow-Origin: *\r\n\r\n"
                } else {
                    "HTTP/1.1 200 OK\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            "Content-Type: $contentType\r\n" +
                            "Content-Length: $fileSize\r\n" +
                            "Connection: keep-alive\r\n" +
                            "Access-Control-Allow-Origin: *\r\n\r\n"
                }

                output.write(responseHeaders.toByteArray())
                output.flush()

                if (method == "HEAD") return

                logger("Streaming file $fileIndex from $startByte to $endByte (size: $contentSize)")

                val savePath = getSavePath()
                val targetFile = File(fileStorage.filePath(fileIndex, savePath))

                var currentPosition = startByte
                val buffer = ByteArray(128 * 1024) // 128KB buffer for high-throughput streaming
                var fileChannel: RandomAccessFile? = null
                var lastPrebufferedPiece = -1
                var lastLookaheadPieces = 0

                try {
                    while (currentPosition <= endByte) {
                        if (myCancel.get() || !isRunning || !torrentHandle.isValid || client.isClosed) break

                        val torrentByteOffset = fileOffset + currentPosition
                        val pieceIndex = (torrentByteOffset / pieceLength).toInt()

                        // Adaptive 25MB Rolling Lookahead Window (dynamic piece budget)
                        if (pieceIndex != lastPrebufferedPiece) {
                            if (lastPrebufferedPiece != -1) {
                                val oldStart = lastPrebufferedPiece
                                val oldEnd = lastPrebufferedPiece + lastLookaheadPieces
                                for (p in oldStart until oldEnd) {
                                    val inHeadOrTail = (p in firstPiece until headEndPiece) || (p in tailStartPiece..lastPiece)
                                    val inNewWindow = (p in pieceIndex until (pieceIndex + 25))
                                    if (!inHeadOrTail && !inNewWindow && p in 0 until totalPieces) {
                                        try {
                                            torrentHandle.resetPieceDeadline(p)
                                            if (!torrentHandle.havePiece(p)) {
                                                torrentHandle.piecePriority(p, Priority.IGNORE)
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }

                            lastPrebufferedPiece = pieceIndex
                            val targetBufferBytes = 25L * 1024L * 1024L
                            val safePieceLen = pieceLength.coerceAtLeast(1L)
                            val lookaheadPieces = (targetBufferBytes / safePieceLen).toInt().coerceIn(4, 30)
                            lastLookaheadPieces = lookaheadPieces
                            for (i in 0 until lookaheadPieces) {
                                val nextPiece = pieceIndex + i
                                if (nextPiece <= lastPiece && nextPiece < totalPieces) {
                                    torrentHandle.piecePriority(nextPiece, Priority.TOP_PRIORITY)
                                    val deadlineMs = if (i == 0) 0 else (100 + i * 150)
                                    torrentHandle.setPieceDeadline(nextPiece, deadlineMs)
                                }
                            }
                        }

                        // 1. Wait for current piece to complete
                        var waitCount = 0
                        var loggedWait = false
                        while (!torrentHandle.havePiece(pieceIndex)) {
                            if (myCancel.get() || !isRunning || !torrentHandle.isValid || client.isClosed) break
                            if (!loggedWait) {
                                logger("Waiting for piece $pieceIndex at position $currentPosition...")
                                loggedWait = true
                            }
                            Thread.sleep(25)
                            waitCount++
                            if (waitCount > 400) { // 10s timeout
                                logger("Timeout waiting for piece $pieceIndex")
                                break
                            }
                        }
                        if (myCancel.get() || !isRunning || !torrentHandle.isValid || client.isClosed) break

                        // 2. Determine how many bytes we can read from current piece
                        val pieceEndByteInTorrent = (pieceIndex.toLong() + 1) * pieceLength
                        val pieceEndPositionInFile = pieceEndByteInTorrent - fileOffset
                        val remainingToRead = endByte - currentPosition + 1
                        val maxInPiece = pieceEndPositionInFile - currentPosition
                        val toRead = minOf(buffer.size.toLong(), remainingToRead, maxInPiece).toInt()

                        if (toRead <= 0) break

                        // 3. Read directly from targetFile on disk
                        var bytesRead = -1
                        var readAttempts = 0
                        while (readAttempts < 200) {
                            if (myCancel.get() || !isRunning || !torrentHandle.isValid || client.isClosed) break
                            try {
                                if (targetFile.exists()) {
                                    if (fileChannel == null) {
                                        fileChannel = RandomAccessFile(targetFile, "r")
                                    }
                                    if (fileChannel.length() > currentPosition) {
                                        fileChannel.seek(currentPosition)
                                        bytesRead = fileChannel.read(buffer, 0, toRead)
                                        if (bytesRead > 0) break
                                    } else {
                                        fileChannel?.close()
                                        fileChannel = null
                                    }
                                }
                            } catch (_: Exception) {
                                fileChannel?.close()
                                fileChannel = null
                            }
                            Thread.sleep(25)
                            readAttempts++
                        }

                        if (bytesRead <= 0) {
                            logger("Failed to read from disk at position $currentPosition")
                            break
                        }

                        // 4. Stream to client socket
                        output.write(buffer, 0, bytesRead)
                        output.flush()
                        currentPosition += bytesRead
                    }
                    if (myCancel.get()) {
                        logger("Stream for file $fileIndex gracefully stopped (superseded by seek)")
                    } else {
                        logger("Finished streaming requested range for file $fileIndex")
                    }
                } catch (e: Exception) {
                    logger("Client disconnected or stream error: ${e.message}")
                } finally {
                    fileChannel?.close()
                }

            } catch (e: Exception) {
                logger("Socket connection error: ${e.message}")
            } finally {
                if (isStreamActive) {
                    TorrentServerManager.onStreamClientDisconnected()
                }
            }
        }
    }

    private fun sendError(output: OutputStream, statusCode: Int, message: String) {
        try {
            val response = """
                HTTP/1.1 $statusCode $message
                Content-Type: text/plain
                Content-Length: ${message.length}
                Connection: close
                
                $message
            """.trimIndent().replace("\n", "\r\n")
            output.write(response.toByteArray())
            output.flush()
        } catch (_: Exception) {}
    }

    private fun getMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "srt" -> "text/srt"
            "vtt" -> "text/vtt"
            "ass", "ssa" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
