package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-performance TCP/HTTP Server that streams H.264 video
 * directly to VLC, ffplay, OBS, or web browsers.
 *
 * Supported VLC connection URLs:
 *   - Network stream HTTP: http://<headunit_ip>:5000/
 *   - Network stream TCP:  tcp/h264://<headunit_ip>:5000
 *
 * Example FFplay (ultra-low latency):
 *   ffplay -f h264 -probesize 32 -flags low_delay -framedrop tcp://<headunit_ip>:5000
 */
object ClusterVideoStreamer {

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val activeClients = CopyOnWriteArrayList<ClientSession>()

    private class ClientSession(
        val socket: Socket,
        val output: OutputStream
    )

    // Cached SPS/PPS header to send immediately to newly connected VLC clients
    @Volatile
    private var cachedSpsPps: ByteArray? = null

    var onClientConnected: (() -> Unit)? = null

    /**
     * Optional sink for the reassembled Annex-B access units, invoked for every frame regardless
     * of whether any TCP client is connected. Wired to the MS9120 USB output when that is enabled;
     * left null otherwise. Invoked before the [activeClients]-empty early return so the MS9120
     * sink still receives frames when no TCP client is attached.
     */
    @Volatile
    var frameListener: ((data: ByteArray, offset: Int, length: Int) -> Unit)? = null

    var isRunning: Boolean = false
        private set

    fun start(port: Int, scope: CoroutineScope) {
        if (isRunning) stop()

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = server
                isRunning = true
                AppLog.i("ClusterVideoStreamer: Streaming server LISTENING on 0.0.0.0:$port (HTTP / Raw TCP for VLC)")

                while (isActive && !server.isClosed) {
                    try {
                        val client = server.accept()
                        client.tcpNoDelay = true
                        client.sendBufferSize = 1024 * 1024

                        // Launch coroutine to handle client handshake (HTTP detection or raw TCP)
                        scope.launch(Dispatchers.IO) {
                            handleNewClient(client)
                        }
                    } catch (e: Exception) {
                        if (!server.isClosed) {
                            AppLog.w("ClusterVideoStreamer: Accept error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.e("ClusterVideoStreamer: Server failed on port $port: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    private suspend fun handleNewClient(client: Socket) {
        try {
            val input: InputStream = client.getInputStream()
            val output: OutputStream = client.getOutputStream()

            // Peek for potential HTTP request (VLC opening http://<IP>:port)
            client.soTimeout = 1000
            val buffer = ByteArray(1024)
            var bytesRead = 0
            try {
                bytesRead = input.read(buffer)
            } catch (_: Exception) {
                // Timeout -> client is a raw TCP listener (ffplay/vlc tcp mode), proceed directly
            }
            client.soTimeout = 0

            val isHttpRequest = if (bytesRead > 0) {
                val reqStr = String(buffer, 0, bytesRead, Charsets.US_ASCII)
                reqStr.startsWith("GET ") || reqStr.startsWith("HEAD ")
            } else {
                false
            }

            if (isHttpRequest) {
                // Respond with HTTP 200 OK stream headers
                val httpHeader = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: video/h264\r\n" +
                        "Server: OpenHeadUnit-ClusterStreamer\r\n" +
                        "Connection: close\r\n" +
                        "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                        "Pragma: no-cache\r\n" +
                        "Expires: 0\r\n" +
                        "Access-Control-Allow-Origin: *\r\n\r\n"
                output.write(httpHeader.toByteArray(Charsets.US_ASCII))
                output.flush()
                AppLog.i("ClusterVideoStreamer: Handled HTTP GET from ${client.remoteSocketAddress}")
            }

            val session = ClientSession(client, output)
            activeClients.add(session)
            AppLog.i("ClusterVideoStreamer: Client active from ${client.remoteSocketAddress} (total: ${activeClients.size})")

            // Send SPS/PPS header immediately so VLC can initialize decoder without delay
            val header = cachedSpsPps
            if (header != null && header.isNotEmpty()) {
                try {
                    output.write(header, 0, header.size)
                    output.flush()
                    AppLog.d("ClusterVideoStreamer: Sent cached SPS/VPS header (${header.size} bytes) to new client")
                } catch (e: Exception) {
                    AppLog.w("ClusterVideoStreamer: Failed to send initial header: ${e.message}")
                }
            }

            // Immediately request an IDR Keyframe from the phone so the newly connected player starts playing instantly!
            try {
                onClientConnected?.invoke()
            } catch (_: Exception) {}
        } catch (e: Exception) {
            AppLog.w("ClusterVideoStreamer: Client setup error from ${client.remoteSocketAddress}: ${e.message}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    fun stop() {
        isRunning = false
        serverJob?.cancel()
        serverJob = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        for (session in activeClients) {
            try {
                session.socket.close()
            } catch (_: Exception) {}
        }
        activeClients.clear()
        frameListener = null
        reset()
        AppLog.i("ClusterVideoStreamer: TCP Server stopped")
    }

    /**
     * Receives a fully reassembled Annex-B video Access Unit (NALU) from AapVideo
     * and forwards it directly to all connected VLC / FFplay clients.
     */
    fun onAssembledFrame(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || offset + length > data.size) return

        // Cache SPS/VPS/PPS parameter sets if present in this frame
        inspectAndCacheSpsPps(data, offset, length)

        // Feed the MS9120 sink (or any other registered frame consumer) before the TCP check,
        // so it keeps receiving frames even with no VLC/ffplay client connected.
        val listener = frameListener
        if (listener != null) {
            try {
                listener(data, offset, length)
            } catch (e: Exception) {
                AppLog.w("ClusterVideoStreamer: frameListener error: ${e.message}")
            }
        }

        if (activeClients.isEmpty()) return

        for (session in activeClients) {
            try {
                session.output.write(data, offset, length)
                session.output.flush()
            } catch (e: Exception) {
                AppLog.d("ClusterVideoStreamer: Client disconnected: ${e.message}")
                try { session.socket.close() } catch (_: Exception) {}
                activeClients.remove(session)
            }
        }
    }

    private var clusterBuffer = java.nio.ByteBuffer.allocate(1024 * 1024 * 4)

    /** A first fragment opened a run that its last fragment has not closed yet. */
    @Volatile
    private var assembling = false

    /**
     * Receives one raw AAP video message of the dedicated cluster channel and forwards the
     * reassembled Annex-B access unit to all connected VLC / FFplay clients.
     *
     * Mirrors the main video path's framing (VideoFragmentAssembler): the flag byte is a
     * bitfield, and after the handshake every message carries the 0x08 encryption bit, so the
     * values that actually arrive are 11 (whole frame), 9 (first fragment), 8 (middle), 10 (last).
     * A whole or first fragment hides its payload behind a 2-byte message type plus an optional
     * 8-byte timestamp, so the NAL starts at offset 10 (with timestamp) or offset 2 (without).
     * Middle and last fragments are raw payload from byte zero - copying them from offset 2, which
     * is what the generic [AapMessage.dataOffset] says, would shift every fragment and corrupt
     * the stream.
     *
     * @return true when the message is raw AV video data and was consumed, false for a signaling
     *   message on the channel (setup, start, stop, focus), which the caller must route to
     *   [com.andrerinas.openheadunit.aap.AapControl] exactly like its main-channel twin - otherwise
     *   the phone never gets its Config response and never starts the stream.
     */
    @Synchronized
    fun process(message: AapMessage): Boolean {
        val flags = message.flags.toInt()
        val totalData = message.data
        val length = message.size
        if (length <= 0 || length > totalData.size) return false

        when (flags) {
            FLAG_SINGLE, FLAG_FIRST -> {
                val nalOffset = findPayloadStart(totalData, length)
                if (nalOffset == null) {
                    // No Annex-B start code at either payload offset: this is not a video frame.
                    assembling = false
                    return false
                }
                val nalLen = length - nalOffset
                if (nalLen <= 0) return true
                clusterBuffer.clear()
                if (clusterBuffer.remaining() < nalLen) {
                    AppLog.w("ClusterVideoStreamer: first fragment of $nalLen bytes does not fit the reassembly buffer, dropping frame")
                    return true
                }
                clusterBuffer.put(totalData, nalOffset, nalLen)
                assembling = true
                if (flags == FLAG_SINGLE) {
                    assembling = false
                    clusterBuffer.flip()
                    onAssembledFrame(clusterBuffer.array(), 0, clusterBuffer.limit())
                    clusterBuffer.clear()
                }
                return true
            }
            FLAG_MIDDLE, FLAG_LAST -> {
                if (!assembling) {
                    // Orphaned fragment: its first fragment never arrived (or was a signaling
                    // message). Consume it so it does not leak into the control path.
                    return true
                }
                if (clusterBuffer.remaining() < length) {
                    AppLog.w("ClusterVideoStreamer: fragment overflow, dropping cluster frame")
                    assembling = false
                    clusterBuffer.clear()
                    return true
                }
                clusterBuffer.put(totalData, 0, length)
                if (flags == FLAG_LAST) {
                    assembling = false
                    clusterBuffer.flip()
                    onAssembledFrame(clusterBuffer.array(), 0, clusterBuffer.limit())
                    clusterBuffer.clear()
                }
                return true
            }
            else -> return false
        }
    }

    /**
     * Length of the Annex B start code at [offset], or 0 if there is none. Bounded by [len] - the
     * message's payload length - and not by [data].size: the SSL layer hands back one reused
     * buffer, so reading past the payload would look at the previous message's leftovers.
     */
    private fun findStartCode(data: ByteArray, offset: Int, len: Int): Int {
        if (offset + 3 > len) return 0
        if (data[offset].toInt() == 0 && data[offset + 1].toInt() == 0) {
            if (data[offset + 2].toInt() == 1) return 3
            if (offset + 4 <= len && data[offset + 2].toInt() == 0 && data[offset + 3].toInt() == 1) return 4
        }
        return 0
    }

    /**
     * Where the NAL payload of a whole/first fragment starts: 10 for a timestamp indication
     * (2 type bytes + 8 timestamp bytes), 2 for a plain media indication, or null when neither
     * spot carries a start code with payload behind it.
     */
    private fun findPayloadStart(data: ByteArray, len: Int): Int? {
        if (payloadStartsAt(data, OFFSET_TIMESTAMP_INDICATION, len)) return OFFSET_TIMESTAMP_INDICATION
        if (payloadStartsAt(data, OFFSET_MEDIA_INDICATION, len)) return OFFSET_MEDIA_INDICATION
        return null
    }

    private fun payloadStartsAt(data: ByteArray, offset: Int, len: Int): Boolean {
        val startCodeLen = findStartCode(data, offset, len)
        return startCodeLen > 0 && len > offset + startCodeLen
    }

    /** Drops assembly state and the cached header so a stale run or a stale SPS/PPS of a previous session cannot leak into the next one. */
    @Synchronized
    fun reset() {
        assembling = false
        clusterBuffer.clear()
        cachedSpsPps = null
    }

    private const val FLAG_SINGLE = 11
    private const val FLAG_FIRST = 9
    private const val FLAG_MIDDLE = 8
    private const val FLAG_LAST = 10
    private const val OFFSET_TIMESTAMP_INDICATION = 10
    private const val OFFSET_MEDIA_INDICATION = 2

    /**
     * Extracts the parameter-set NALs from an access unit and caches them, with start codes,
     * concatenated. Caching only the whole access unit that first carries a VPS/SPS (the old
     * behavior) left clients without a PPS whenever the phone split the parameter sets across
     * NALs - which is what makes ffplay fail with "PPS id out of range" on a mid-GOP connect.
     *
     * H.264: NAL 7 (SPS), NAL 8 (PPS). H.265 / HEVC: NAL 32 (VPS), NAL 33 (SPS), NAL 34 (PPS).
     */
    private fun inspectAndCacheSpsPps(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (length < 9) return
        val end = offset + length
        val parts = ArrayList<ByteArray>(3)
        var i = offset
        while (i + 5 <= end) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val nalHeader = data[i + 4].toInt() and 0xFF
                val h264Nal = nalHeader and 0x1F
                val hevcNal = (nalHeader ushr 1) and 0x3F
                if (h264Nal == 7 || h264Nal == 8 || hevcNal == 32 || hevcNal == 33 || hevcNal == 34) {
                    // This NAL runs until the next start code (or the end of the access unit).
                    var nalEnd = end
                    var j = i + 4
                    while (j + 4 <= end) {
                        if (data[j] == 0.toByte() && data[j + 1] == 0.toByte() && data[j + 2] == 0.toByte() && data[j + 3] == 1.toByte()) {
                            nalEnd = j
                            break
                        }
                        j++
                    }
                    parts.add(data.copyOfRange(i, nalEnd))
                    i = nalEnd - 4
                }
            }
            i++
        }
        if (parts.isNotEmpty()) {
            val cache = parts.fold(0) { a, p -> a + p.size }
            val out = ByteArray(cache)
            var pos = 0
            for (p in parts) {
                System.arraycopy(p, 0, out, pos, p.size)
                pos += p.size
            }
            cachedSpsPps = out
            AppLog.d("ClusterVideoStreamer: Cached ${parts.size} parameter-set NAL(s) (${out.size} bytes)")
        }
    }
}
