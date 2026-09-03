package com.andrerinas.openheadunit.aap

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.widget.FrameLayout
import com.andrerinas.openheadunit.decoder.video.ParameterSetInspector
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import java.nio.ByteBuffer

/**
 * Debug aid: renders the dedicated cluster video stream (AAP channel `ID_VID_CLUSTER`) in a small
 * floating window *on top of* the normal Android Auto projection, so both pictures are visible at
 * the same time.
 *
 * The main projection and the cluster are two independent H.264/HEVC streams, and there is a single
 * shared [com.andrerinas.openheadunit.decoder.video.VideoDecoder] for the main one. This overlay owns
 * its *own* MediaCodec and its *own* [SurfaceView], so it decodes the cluster without touching the
 * main decoder at all - which is the whole point of the feature (the "swap the streams" approach
 * broke the connection; showing the cluster beside the main one does not).
 *
 * Fed one reassembled Annex-B access unit at a time from
 * [com.andrerinas.openheadunit.aap.ClusterVideoStreamer.frameListener], on the reader thread - the
 * same thread the main stream is decoded on, so no extra worker is needed for a debug view.
 */
internal object ClusterOverlay {

    /** Set on [attach]; owned by the projection activity for the life of the overlay. */
    @Volatile private var activity: Context? = null

    private var surfaceView: SurfaceView? = null
    private var container: FrameLayout? = null

    @Volatile private var surface: Surface? = null
    private var codec: MediaCodec? = null
    @Volatile private var codecReady = false
    private val bufferInfo = MediaCodec.BufferInfo()

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            synchronized(this@ClusterOverlay) {
                surface = holder.surface
            }
            AppLog.i("ClusterOverlay: surface created")
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            // Nothing to do: decoding to a MediaCodec-owned surface is self-sizing off the SPS.
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            synchronized(this@ClusterOverlay) {
                surface = null
            }
            releaseCodec()
        }
    }

    /** Adds the overlay to [container], top-left, at ~40% of the screen width. Idempotent. */
    fun attach(ctx: Context, container: FrameLayout) {
        if (surfaceView != null) return
        activity = ctx.applicationContext
        val metrics = ctx.resources.displayMetrics
        val w = (metrics.widthPixels * 0.4).toInt()
        val h = (w * 9 / 16).coerceAtMost((metrics.heightPixels * 0.4).toInt())
        val view = SurfaceView(ctx).apply {
            setZOrderMediaOverlay(true)
            setZOrderOnTop(false)
            val params = FrameLayout.LayoutParams(w, h, android.view.Gravity.TOP or android.view.Gravity.START).apply {
                setMargins(48, 48, 0, 0)
            }
            layoutParams = params
        }
        view.holder.addCallback(surfaceCallback)
        container.addView(view)
        this.container = container
        this.surfaceView = view
        AppLog.i("ClusterOverlay: attached (${w}x$h)")
    }

    fun detach() {
        surfaceView?.let {
            try {
                it.holder.removeCallback(surfaceCallback)
            } catch (_: Exception) {}
            container?.removeView(it)
        }
        surfaceView = null
        container = null
        activity = null
        synchronized(this) {
            surface = null
        }
        releaseCodec()
    }

    /**
     * Feeds one reassembled cluster Annex-B access unit into the overlay's own decoder. Called from
     * the reader thread via [com.andrerinas.openheadunit.aap.ClusterVideoStreamer.frameListener].
     */
    fun onUnit(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || offset + length > data.size) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val s: Surface? = surface
        if (s == null || !s.isValid) return

        val unit = data.copyOfRange(offset, offset + length)
        ensureDecoder(s, unit)

        val c = codec ?: return
        val inIdx = c.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            val inputBuffer = c.getInputBuffer(inIdx)
            if (inputBuffer != null) {
                inputBuffer.clear()
                if (inputBuffer.remaining() < unit.size) {
                    AppLog.w("ClusterOverlay: access unit of ${unit.size} bytes exceeds input buffer")
                } else {
                    inputBuffer.put(unit)
                    c.queueInputBuffer(inIdx, 0, unit.size, 0, 0)
                }
            }
        }
        drainOutput()
    }

    private fun drainOutput() {
        val c = codec ?: return
        while (true) {
            val outIdx = c.dequeueOutputBuffer(bufferInfo, 0)
            if (outIdx < 0) return
            if (bufferInfo.size > 0 &&
                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0
            ) {
                c.releaseOutputBuffer(outIdx, true)
            } else {
                c.releaseOutputBuffer(outIdx, false)
            }
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
        }
    }

    /** Builds the decoder on the first access unit carrying an SPS, sized off the SPS. */
    private fun ensureDecoder(s: Surface, firstUnit: ByteArray) {
        synchronized(this) {
            if (codecReady) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val sets = scanParameterSets(firstUnit) ?: return

            val (w, h) = if (sets.width in 160..7680 && sets.height in 160..4320) {
                Pair(sets.width, sets.height)
            } else {
                parseResolution(
                    activity?.let { Settings(it.applicationContext).clusterVideoResolution } ?: "1280x720"
                )
            }

            val format = MediaFormat.createVideoFormat(sets.mime, w, h)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            if (sets.mime == "video/hevc") {
                val combined = (sets.vps ?: ByteArray(0)) + sets.sps + (sets.pps ?: ByteArray(0))
                format.setByteBuffer("csd-0", ByteBuffer.wrap(combined))
            } else {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(sets.sps))
                sets.pps?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            }

            val c = MediaCodec.createDecoderByType(sets.mime)
            c.configure(format, s, null, 0)
            c.start()
            codec = c
            codecReady = true
            AppLog.i("ClusterOverlay: decoder ready (${sets.mime}, ${w}x$h)")
        }
    }

    private fun releaseCodec() {
        synchronized(this) {
            codecReady = false
            try {
                codec?.stop()
            } catch (_: Exception) {}
            try {
                codec?.release()
            } catch (_: Exception) {}
            codec = null
        }
    }

    private class ParsedSets(
        val mime: String,
        val sps: ByteArray,
        val pps: ByteArray?,
        val vps: ByteArray?,
        val width: Int,
        val height: Int,
    )

    /** Collects the parameter sets and picture size out of one access unit; null if no SPS yet. */
    private fun scanParameterSets(unit: ByteArray): ParsedSets? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var vps: ByteArray? = null
        var width = 0
        var height = 0

        forEachNal(unit) { nal, headerOffset ->
            val first = nal[headerOffset].toInt()
            val avcType = first and 0x1F
            val hevcType = (first and 0x7E) shr 1
            when {
                avcType == 7 -> {
                    sps = nal
                    ParameterSetInspector.parseH264Sps(nal, headerOffset, nal.size - headerOffset)
                        ?.let { width = it.width; height = it.height }
                }
                avcType == 8 -> pps = nal
                hevcType == 32 -> vps = nal
                hevcType == 33 -> {
                    sps = nal
                    ParameterSetInspector.parseHevcSps(nal, headerOffset, nal.size - headerOffset)
                        ?.let { width = it.width; height = it.height }
                }
                hevcType == 34 -> pps = nal
            }
        }

        val spsNal = sps ?: return null
        val mime = if ((spsNal[4].toInt() and 0x7E) shr 1 == 33) "video/hevc" else "video/avc"
        return ParsedSets(mime, spsNal, pps, vps, width, height)
    }

    /**
     * Invokes [cb] for each NAL in an Annex-B buffer. [cb] receives data starting with a normalised
     * 4-byte start code and the NAL header byte's offset within it (always 4).
     */
    private fun forEachNal(unit: ByteArray, cb: (ByteArray, Int) -> Unit) {
        val n = unit.size
        var i = 0
        while (i < n) {
            var zeros = 0
            while (i + zeros < n && unit[i + zeros] == 0.toByte()) zeros++
            if (i + zeros < n && unit[i + zeros] == 1.toByte() && zeros >= 2) {
                val start = i
                val header = i + zeros
                var end = n
                var j = header
                while (j + 3 < n) {
                    val c = unit[j].toInt()
                    if (c == 0 && unit[j + 1].toInt() == 0 &&
                        (unit[j + 2].toInt() == 1 ||
                            (j + 3 < n && unit[j + 2].toInt() == 0 && unit[j + 3].toInt() == 1))
                    ) {
                        end = j
                        break
                    }
                    j++
                }
                val raw = unit.copyOfRange(start, end)
                val fixed = if (zeros == 2) {
                    ByteArray(raw.size + 1).apply {
                        this[0] = 0
                        System.arraycopy(raw, 0, this, 1, raw.size)
                    }
                } else raw
                cb(fixed, 4)
                i = end
            } else {
                i++
            }
        }
    }

    private fun parseResolution(res: String): Pair<Int, Int> {
        val parts = res.split("x", "X")
        val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1280
        val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 720
        return if (w > 0 && h > 0) Pair(w, h) else Pair(1280, 720)
    }
}
