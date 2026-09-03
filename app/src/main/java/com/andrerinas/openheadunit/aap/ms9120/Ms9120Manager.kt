package com.andrerinas.openheadunit.aap.ms9120

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.decoder.video.ParameterSetInspector
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import java.nio.ByteBuffer

/**
 * Owns the MS9120 USB dongle for as long as the user has MS9120 output enabled.
 *
 * Logic:
 * - If the video decoder is producing decoded frames, display them on the MS9120 screen.
 * - If no decoded frames are arriving (> 500ms or disconnected), display "Android Auto not connected".
 */
object Ms9120Manager {

    private const val ACTION_USB_PERMISSION = "com.andrerinas.openheadunit.MS9120_USB_PERMISSION"
    private const val INPUT_TIMEOUT_US = 10_000L

    var onKeyframeNeeded: (() -> Unit)? = null
    @Volatile private var hasFirstDecodedFrame = false
    @Volatile private var lastDecodedFrameMs = 0L
    @Volatile private var pipelineStartTimeMs = 0L
    @Volatile private var lastKeyframeRequestMs = 0L

    @Volatile private var context: Context? = null
    @Volatile private var device: MS9120Device? = null

    /** True while the USB dongle is open and initialised. */
    @Volatile private var attached = false

    /** True when Android Auto session is active. */
    @Volatile private var aaConnected = false

    /** True while decoding frames. */
    @Volatile private var running = false
    @Volatile private var codecReady = false
    private var decoder: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var senderThread: Thread? = null
    private var receiver: BroadcastReceiver? = null

    private var frameBuffer: ByteArray = ByteArray(0)
    private val frameLock = Any()
    private var pendingFrame: ByteArray? = null

    /** Cached idle-screen pixels and geometry. */
    private var idlePixels: ByteArray? = null
    private var idleFrameW = 0
    private var idleFrameH = 0
    private var idleFormat: WireFormat? = null

    /**
     * "Now playing" toast: a centred rounded-rect card (title big, artist small) drawn over the
     * frame for a few seconds after a track changes. [toastCard] holds the card's ARGB pixels
     * (transparent outside the rounded rect) plus where it sits in the wire frame; the sender loop
     * composites it over each outgoing frame until [toastUntilMs] elapses. Guarded by [toastLock]
     * because it is written on the AAP media-metadata thread and read on the sender thread.
     */
    @Volatile private var toastCard: MediaCard? = null
    private var lastToastTitle: String? = null
    private var lastToastArtist: String? = null
    private var lastToastHadArt: Boolean = false
    /** When set, the next now-playing event after an AA connect is suppressed: it carries the
     *  track already playing (not a change), so showing the toast would be noise. Cleared after
     *  that one event, so subsequent real track changes still fire the toast. */
    @Volatile private var suppressFirstMetaAfterConnect = false
    @Volatile private var toastUntilMs = 0L
    @Volatile private var toastStartMs = 0L
    /** Previous card kept so a track change can crossfade the album-art region between the old
     *  and new art while the toast is still on screen. Null until a swap occurs. */
    private var crossfadeFromCard: MediaCard? = null
    @Volatile private var crossfadeStartMs = 0L
    private val toastLock = Any()
    private val toastDurationMs = 6000L
    private val toastFadeMs = 350L
    /** How long the album-art crossfade between two tracks lasts while the toast stays up. */
    private val artCrossfadeMs = 300L
    /** Diagnostics: how many now-playing metadata events have arrived this session, and when the
     *  first one landed relative to AA connect (to tell "phone is slow" from "we suppressed it"). */
    @Volatile private var mediaMetaCount = 0L
    @Volatile private var firstMetaElapsedMs = -1L

    private class MediaCard(
        val pixels: IntArray,
        val l: Int,
        val t: Int,
        val w: Int,
        val h: Int,
        // Bounding box (in card pixels) of the circular album-art region. Used so the
        // crossfade on a track change blends ONLY this region; the title/artist and the pill
        // background switch instantly to the new card.
        val artL: Int,
        val artT: Int,
        val artSize: Int,
        // The card bitmap is the FULL cluster-source frame; [fullW]x[fullH] are its dimensions
        // (== the cluster stream resolution it was rendered at). drawToastOver() resamples this
        // into the video display region with the same scale factors the video uses, so the toast
        // stretches/squashes exactly like the picture.
        val fullW: Int,
        val fullH: Int
    )

    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile private var unitsIn = 0L
    private var outFrames = 0L
    private var imageNulls = 0L
    private var framesSent = 0L
    private var sendFails = 0L
    private var lastImageDiagMs = 0L

    /** Source (decoded cluster-stream) resolution — set once the decoder is configured.
     *  Used to compute the video display region in the output frame (same geometry as
     *  YuvConverter) so the toast is centred on the actual video content, not the full
     *  dongle frame. 0 until the first frame. */
    @Volatile private var clusterSrcW = 0
    @Volatile private var clusterSrcH = 0
    @Volatile private var imgDimsLogged = false

    @Volatile private var lastSendLogMs = 0L
    @Volatile private var sendLogBaseline = 0L
    @Volatile private var sendLogBaselineMs = 0L
    @Volatile private var lastSendMsAvg = 0

    @Volatile private var lastInputLogMs = 0L
    @Volatile private var inputLogBaseline = 0L
    @Volatile private var inputLogBaselineMs = 0L

    fun attach(ctx: Context, settings: Settings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            AppLog.e("MS9120: requires Android O (API 26) for MediaCodec image decode")
            return
        }
        val appContext = ctx.applicationContext
        if (attached) {
            AppLog.i("MS9120: already attached")
            return
        }
        synchronized(this) {
            if (attached) return
            context = appContext
            attachReceiver(appContext, settings)
        }
        AppLog.i("MS9120: attach requested")
        findAndAttach(appContext, settings)
    }

    fun onResolutionOrFormatChanged(ctx: Context) {
        val appContext = ctx.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        Thread({
            try {
                val wasConnected = aaConnected
                detach()
                attach(appContext, Settings(appContext))
                if (wasConnected) onAndroidAutoConnected()
            } catch (e: Exception) {
                AppLog.e("MS9120: re-init after resolution/format change failed: ${e.message}")
            }
        }, "MS9120-Reinit").start()
    }

    @Synchronized
    fun onAndroidAutoConnected() {
        aaConnected = true
        running = true
        codecReady = false
        hasFirstDecodedFrame = false
        lastDecodedFrameMs = 0L
        pipelineStartTimeMs = SystemClock.elapsedRealtime()
        lastKeyframeRequestMs = 0L
        // A fresh AA session: the phone's first now-playing event carries the track already
        // playing (not a change), so suppress that single event — toasts only fire on real
        // track changes thereafter (or when the user forces one via the key press).
        mediaMetaCount = 0L
        firstMetaElapsedMs = -1L
        lastToastTitle = null
        lastToastArtist = null
        lastToastHadArt = false
        suppressFirstMetaAfterConnect = true
        synchronized(frameLock) { pendingFrame = null }
        AppLog.i("MS9120: Android Auto connected, awaiting decoded frames")
    }

    @Synchronized
    fun onAndroidAutoDisconnected() {
        aaConnected = false
        running = false
        codecReady = false
        lastDecodedFrameMs = 0L
        synchronized(frameLock) { pendingFrame = null }
        releaseDecoder()
        AppLog.i("MS9120: Android Auto disconnected, returning to idle screen")
    }

    @Synchronized
    fun detach() {
        // Full teardown — the MS9120 option was turned off (or the app is shutting down).
        // Drop the AA/decode state, release the dongle, and stop watching the USB bus.
        aaConnected = false
        running = false
        releasePipeline()
        unregisterReceiver()
        context = null
        AppLog.i("MS9120: detached (receiver unregistered)")
    }

    /**
     * The dongle was physically unplugged while (possibly) still in use. Release the USB device
     * but keep the receiver armed AND keep the AA connection state: when it's plugged back in,
     * Android's ACTION_USB_DEVICE_ATTACHED re-triggers findAndAttach() and the cluster video
     * resumes without the user re-opening the app or re-granting permission.
     */
    @Synchronized
    fun onDevicePhysicallyDetached() {
        if (!attached) return
        releasePipeline()
        AppLog.i("MS9120: dongle unplugged — receiver stays armed for auto-reconnect")
    }

    @Synchronized
    private fun releasePipeline() {
        codecReady = false
        hasFirstDecodedFrame = false
        lastDecodedFrameMs = 0L
        attached = false
        toastUntilMs = 0L
        lastToastTitle = null
        lastToastArtist = null
        lastToastHadArt = false
        synchronized(toastLock) { toastCard = null }

        senderThread?.interrupt()
        try {
            senderThread?.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        senderThread = null

        releaseDecoder()
        synchronized(frameLock) { pendingFrame = null }

        try {
            device?.close()
        } catch (_: Exception) {}
        device = null
        frameBuffer = ByteArray(0)
    }

    private fun unregisterReceiver() {
        context?.let { ctx ->
            try {
                ctx.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
        receiver = null
    }

    private fun attachReceiver(appContext: Context, settings: Settings) {
        val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                when (intent.action) {
                    ACTION_USB_PERMISSION -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        AppLog.i("MS9120: USB permission broadcast granted=$granted")
                        if (granted && d != null && isMs9120(d)) {
                            bringUp(appContext, usb, settings, d)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        if (d != null && isMs9120(d)) {
                            AppLog.i("MS9120: USB MS9120 attached")
                            findAndAttach(appContext, settings)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        if (d != null && isMs9120(d)) {
                            // Physical unplug: release the dongle but keep the receiver registered
                            // and the AA state, so a re-plug auto-reconnects instead of waiting on
                            // the next app resume / AA reconnect.
                            onDevicePhysicallyDetached()
                        }
                    }
                }
            }
        }
        this.receiver = receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun findAndAttach(appContext: Context, settings: Settings) {
        val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        for (d in usb.deviceList.values) {
            if (isMs9120(d)) {
                AppLog.i("MS9120: found dongle VID:0x${d.vendorId.toString(16)} PID:0x${d.productId.toString(16)}")
                if (usb.hasPermission(d)) {
                    bringUp(appContext, usb, settings, d)
                } else {
                    AppLog.i("MS9120: requesting USB permission...")
                    val pending = PendingIntent.getBroadcast(
                        appContext, 0, Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                    )
                    usb.requestPermission(d, pending)
                }
                return
            }
        }
        AppLog.w("MS9120: no MS9120 dongle found (expected VID 0x534D/0x345F)")
    }

    private fun isMs9120(device: UsbDevice): Boolean {
        val vid = device.vendorId
        val pid = device.productId
        return (vid == 0x534D && (pid == 0x6021 || pid == 0x0821)) || (vid == 0x345F && pid == 0x9132)
    }

    @Synchronized
    private fun bringUp(appContext: Context, usb: UsbManager, settings: Settings, usbDevice: UsbDevice) {
        if (attached) {
            AppLog.d("MS9120: already attached, ignoring re-setup")
            return
        }
        val msDevice = MS9120Device(usb, usbDevice)
        if (!msDevice.open()) {
            AppLog.e("MS9120: open() failed")
            return
        }

        val res = settings.ms9120Resolution
        val color = settings.ms9120ColorFormat
        msDevice.inputRes = toInputRes(res)
        msDevice.wireFormat = toWireFormat(color)
        msDevice.frameSkip = settings.ms9120FrameSkip
        msDevice.stretch = settings.ms9120Stretch

        if (!msDevice.initDisplay()) {
            AppLog.e("MS9120: initDisplay() failed, closing")
            msDevice.close()
            return
        }

        device = msDevice
        frameBuffer = ByteArray(msDevice.framePixelsBytes).apply {
            if (msDevice.wireFormat == WireFormat.YUV422) {
                var i = 0
                while (i + 3 < size) {
                    this[i]     = 128.toByte()
                    this[i + 1] = 16.toByte()
                    this[i + 2] = 128.toByte()
                    this[i + 3] = 16.toByte()
                    i += 4
                }
            }
        }
        attached = true

        senderThread = Thread({ runSenderLoop(msDevice) }, "MS9120-Sender").apply {
            isDaemon = true
            start()
        }

        AppLog.i("MS9120: attached and permanent sender loop active (${res.width}x${res.height}, ${color.name})")
    }

    /**
     * Single persistent loop:
     * - Before the first decoded frame (or when disconnected), sends "Android Auto not connected".
     * - Once live decoded frames start arriving, sends every decoded frame and holds the image
     *   during static frames / pauses without flashing the idle screen.
     */
    private fun runSenderLoop(msDevice: MS9120Device) {
        try {
            while (attached) {
                val frame: ByteArray? = synchronized(frameLock) {
                    val f = pendingFrame
                    pendingFrame = null
                    f
                }
                if (frame != null) {
                    hasFirstDecodedFrame = true
                    // 'frame' is a private copyOf taken on the decode side, so it is safe to mutate
                    // here. Composite the "now playing" card over it (no-op when no card is queued).
                    drawToastOver(msDevice, frame)
                    val t0 = SystemClock.elapsedRealtime()
                    if (msDevice.sendFrame(frame) < 0) sendFails++ else framesSent++
                    val sendMs = SystemClock.elapsedRealtime() - t0
                    maybeLogSendRate(sendMs)
                } else if (!hasFirstDecodedFrame || !running) {
                    showIdleFrame(msDevice)
                    Thread.sleep(30)
                } else {
                    Thread.sleep(10)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            AppLog.e("MS9120: sender loop error: ${e.message}")
        }
    }

    private fun showIdleFrame(msDevice: MS9120Device) {
        val ctx = context ?: return
        val w = msDevice.inputRes.width
        val h = msDevice.inputRes.height
        val buffer = frameBuffer
        if (buffer.size != msDevice.framePixelsBytes) return

        if (idleFrameW != w || idleFrameH != h || idleFormat != msDevice.wireFormat || idlePixels == null) {
            val text = ctx.getString(R.string.ms9120_idle_text)
            idlePixels = renderIdleFrame(ctx, w, h, text, msDevice.wireFormat)
            idleFrameW = w
            idleFrameH = h
            idleFormat = msDevice.wireFormat
        }
        if (idlePixels != null && idlePixels!!.size == buffer.size) {
            System.arraycopy(idlePixels!!, 0, buffer, 0, buffer.size)
            drawToastOver(msDevice, buffer) // no-op when no card is queued
            msDevice.sendFrame(buffer)
        }
    }

    /**
     * Composite the "now playing" card over [out] (a wire-format pixel buffer owned by the caller)
     * if a toast is still on screen. In-place, so [out] must be the buffer about to be sent.
     * Returns true if a toast was drawn. Cheap no-op the whole time no card is queued.
     */
    private fun drawToastOver(msDevice: MS9120Device, out: ByteArray): Boolean {
        if (SystemClock.elapsedRealtime() >= toastUntilMs) {
            synchronized(toastLock) { toastCard = null }
            return false
        }
        val card = synchronized(toastLock) { toastCard } ?: return false
        val fromCard = synchronized(toastLock) { crossfadeFromCard }
        val buf = out
        if (buf.size != msDevice.framePixelsBytes) return false
        // The card bitmap is the full cluster-source frame; guard its own dimensions are sane
        // (the resampler is bounded by card.fullW x card.fullH, independent of the dongle output).
        val fw = msDevice.inputRes.width
        val fh = msDevice.inputRes.height
        if (card.fullW <= 0 || card.fullH <= 0) return false

        // Fade in at the start and fade out at the end. fade=255 -> fully opaque card; fade=0 ->
        // the card is transparent and nothing is drawn (early-out).
        val now = SystemClock.elapsedRealtime()
        val fade = if (now < toastStartMs + toastFadeMs) {
            // Fading in: 0 -> 255 over the first toastFadeMs.
            ((now - toastStartMs) * 255L / toastFadeMs).toInt().coerceIn(0, 255)
        } else if (now > toastUntilMs - toastFadeMs) {
            // Fading out: 255 -> 0 over the last toastFadeMs.
            ((toastUntilMs - now) * 255L / toastFadeMs).toInt().coerceIn(0, 255)
        } else {
            255
        }
        if (fade <= 0) return false

        // Album-art crossfade between two tracks (only while the toast stays on screen). The new
        // card's text/background are used everywhere; ONLY the circular art region blends from the
        // previous card's art to the new one over [artCrossfadeMs]. fromPixels is null once the
        // crossfade window has elapsed, so the extra blend stops (and the array is released).
        val fromPixels: IntArray?
        val fromW: Int
        val mixPct: Int
        val artL0 = card.artL
        val artX1 = card.artL + card.artSize
        val artY0 = card.artT
        val artY1 = card.artT + card.artSize
        // Only crossfade when both cards share the same source-frame size; otherwise the art-region
        // index into the old card could run off its edges, so we switch instantly.
        val canCross = fromCard != null && fromCard.fullW == card.fullW && fromCard.fullH == card.fullH
        if (canCross) {
            val fc = fromCard!!
            val elapsed = now - crossfadeStartMs
            if (elapsed >= artCrossfadeMs) {
                synchronized(toastLock) { crossfadeFromCard = null }
                fromPixels = null; fromW = 0; mixPct = 0
            } else {
                fromPixels = fc.pixels
                fromW = fc.fullW
                mixPct = (elapsed * 100L / artCrossfadeMs).toInt().coerceIn(0, 100)
            }
        } else {
            if (fromCard != null && (now - crossfadeStartMs) >= artCrossfadeMs) {
                synchronized(toastLock) { crossfadeFromCard = null }
            }
            fromPixels = null; fromW = 0; mixPct = 0
        }

        // The card was rendered in the CLUSTER-SOURCE resolution (card.fullW x card.fullH) so its
        // text/art have natural aspect ratios. We now resample it into the video display [region]
        // with the SAME scale factors the video uses (see videoDisplayRegion), so the toast
        // stretches / squashes exactly like the picture. Outside the region we draw nothing.
        val region = videoDisplayRegion(fw, fh)
        val fullW = card.fullW
        val fullH = card.fullH
        val rw = region.width()
        val rh = region.height()
        if (rw <= 0 || rh <= 0 || fullW <= 0 || fullH <= 0) return false

        // Linear mapping from region-local (i, j) to source coords (sx, sy).
        val xIdx = IntArray(rw)
        for (i in 0 until rw) xIdx[i] = (i * fullW / rw).coerceIn(0, fullW - 1)
        val regionLeft = region.left

        val argb = card.pixels
        when (msDevice.wireFormat) {
            WireFormat.RGB888 -> {
                // Wire is BGR, 3 bytes per pixel.
                for (j in 0 until rh) {
                    val sy = (j * fullH / rh).coerceIn(0, fullH - 1)
                    val baseIdx = sy * fullW
                    val outRow = region.top + j
                    var x = regionLeft
                    var dst = (outRow * fw + regionLeft) * 3
                    for (i in 0 until rw) {
                        val sx = xIdx[i]
                        val cRaw = argb[baseIdx + sx]
                        val c = if (fromPixels != null && sx in artL0 until artX1 && sy in artY0 until artY1) {
                            blendArgb(cRaw, fromPixels[sy * fromW + sx], mixPct)
                        } else cRaw
                        val a = (((c ushr 24) and 0xFF) * fade) / 255
                        if (a != 0) {
                            val inv = 255 - a
                            val b = (c and 0xFF)
                            val g = ((c shr 8) and 0xFF)
                            val r = ((c shr 16) and 0xFF)
                            val ob = buf[dst].toInt() and 0xFF
                            val og = buf[dst + 1].toInt() and 0xFF
                            val or = buf[dst + 2].toInt() and 0xFF
                            buf[dst]     = (((b * a + ob * inv) / 255) and 0xFF).toByte()
                            buf[dst + 1] = (((g * a + og * inv) / 255) and 0xFF).toByte()
                            buf[dst + 2] = (((r * a + or * inv) / 255) and 0xFF).toByte()
                        }
                        dst += 3
                        x++
                    }
                }
            }
            WireFormat.YUV422 -> {
                // Wire is UYVY: 4 bytes per pixel pair -> 2 bytes per pixel.
                for (j in 0 until rh) {
                    val sy = (j * fullH / rh).coerceIn(0, fullH - 1)
                    val baseIdx = sy * fullW
                    val outRow = region.top + j
                    var x = regionLeft
                    var dst = (outRow * fw + regionLeft) * 2
                    for (i in 0 until rw) {
                        val sx = xIdx[i]
                        val cRaw = argb[baseIdx + sx]
                        val c = if (fromPixels != null && sx in artL0 until artX1 && sy in artY0 until artY1) {
                            blendArgb(cRaw, fromPixels[sy * fromW + sx], mixPct)
                        } else cRaw
                        val a = (((c ushr 24) and 0xFF) * fade) / 255
                        if (a != 0) {
                            val inv = 255 - a
                            val r = ((c shr 16) and 0xFF)
                            val g = ((c shr 8) and 0xFF)
                            val b = (c and 0xFF)
                            // Rec.601 luma + approximate chroma from a single colour (no neighbour
                            // available here). Good enough for a text card over video. NOTE the
                            // chroma: `shr` is a Kotlin infix FUNCTION, so `(x shr 1 + 128)` would
                            // parse as `shr (1 + 128)` = 0; the +128 neutral offset must be added
                            // to the *result*, not the shift count.
                            val y = ((r * 77 + g * 150 + b * 29) ushr 8).coerceIn(0, 255)
                            val u = ((b - y) / 2 + 128).coerceIn(0, 255)
                            val v = ((r - y) / 2 + 128).coerceIn(0, 255)
                            val uEven = (x and 1) == 0
                            if (uEven) {
                                val ou = buf[dst].toInt() and 0xFF
                                buf[dst] = (((u * a + ou * inv) / 255) and 0xFF).toByte()
                            } else {
                                val ov = buf[dst].toInt() and 0xFF
                                buf[dst] = (((v * a + ov * inv) / 255) and 0xFF).toByte()
                            }
                            val oy = buf[dst + 1].toInt() and 0xFF
                            buf[dst + 1] = (((y * a + oy * inv) / 255) and 0xFF).toByte()
                        }
                        dst += 2
                        x++
                    }
                }
            }
            WireFormat.RGB565 -> {
                // Wire is RGB565 little-endian, 2 bytes per pixel.
                for (j in 0 until rh) {
                    val sy = (j * fullH / rh).coerceIn(0, fullH - 1)
                    val baseIdx = sy * fullW
                    val outRow = region.top + j
                    var x = regionLeft
                    var dst = (outRow * fw + regionLeft) * 2
                    for (i in 0 until rw) {
                        val sx = xIdx[i]
                        val cRaw = argb[baseIdx + sx]
                        val c = if (fromPixels != null && sx in artL0 until artX1 && sy in artY0 until artY1) {
                            blendArgb(cRaw, fromPixels[sy * fromW + sx], mixPct)
                        } else cRaw
                        val a = (((c ushr 24) and 0xFF) * fade) / 255
                        if (a != 0) {
                            val inv = 255 - a
                            val r = (((c shr 16) and 0xFF) shr 3)
                            val g = (((c shr 8) and 0xFF) shr 2)
                            val b = ((c and 0xFF) shr 3)
                            val c565 = (r shl 11) or (g shl 5) or b
                            val ov = buf[dst].toInt() and 0xFF
                            val ov2 = buf[dst + 1].toInt() and 0xFF
                            val oVal = ov or (ov2 shl 8)
                            val nb = (c565 * a + oVal * inv) / 255
                            buf[dst] = (nb and 0xFF).toByte()
                            buf[dst + 1] = ((nb shr 8) and 0xFF).toByte()
                        }
                        dst += 2
                        x++
                    }
                }
            }
        }
        return true
    }

    /**
     * Queues a "now playing" toast: a centred pill-shaped card showing optional circular [albumArt]
     * on the left, the [title] (big) and [artist] (small). Composites over the cluster picture for
     * [toastDurationMs]. Called from the AAP media-metadata thread when the track changes; ignored
     * while the dongle is down or when the same title/artist was already shown (so we only pop on
     * an actual track change).
     *
     * Fade behaviour: a fresh toast (nothing on screen yet) fades IN over [toastFadeMs]. If a toast
     * is already on screen and the track changes, we only swap the content and extend the timer —
     * the fade-in is NOT repeated (no flicker on rapid track changes); only the fade-out still
     * applies when it finally disappears.
     *
     * Late album art: the phone's now-playing metadata often arrives in two events — text first,
     * the art a few seconds later. If the art lands while the toast is up, we swap the card in
     * place; if it lands after the toast faded out we do NOT re-show the toast.
     *
     * @param force When true (user-triggered, e.g. key press), bypasses both the first-event
     *              suppression and the dedup so the toast is always shown.
     */
    @Synchronized
    fun showMediaToast(title: String, artist: String, albumArt: ByteArray? = null, force: Boolean = false) {
        val msDevice = device ?: return
        if (!attached) return
        val t = title.trim()
        val a = artist.trim()
        if (t.isEmpty() && a.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val toastOnScreen = now < toastUntilMs
        val hasArt = albumArt != null && albumArt.isNotEmpty()

        // After an AA connect the phone sends the current track as the first metadata event.
        // The user does not want that "startup" toast — suppress it unless the call is forced
        // (e.g. the user pressed the "show toast" key).
        if (!force && suppressFirstMetaAfterConnect) {
            suppressFirstMetaAfterConnect = false
            AppLog.v("MS9120: suppressing first now-playing toast (AA connect, not a track change)")
            return
        }

        // Same track already shown. If the album art just arrived (a second metadata event for
        // the same song carrying the art that the first one lacked) and the toast is still on
        // screen, re-render with the art and swap the card in place — no re-fade, no timer reset.
        if (!force && t == lastToastTitle && a == lastToastArtist) {
            if (hasArt && !lastToastHadArt && toastOnScreen) {
                try {
                    val ctx = context ?: return
                    val src = cardSourceRes(msDevice)
                    val card = renderMediaCard(ctx, src.first, src.second, t, a, albumArt)
                    synchronized(toastLock) { toastCard = card }
                    lastToastHadArt = true
                    AppLog.i("MS9120: now-playing toast gained late album art (title len=${t.length})")
                } catch (e: Exception) {
                    AppLog.e("MS9120: failed to re-render now-playing toast with art: ${e.message}")
                }
            }
            return
        }

        val ctx = context ?: return
        try {
            val src = cardSourceRes(msDevice)
            AppLog.i(
                "MS9120: toast src=${src.first}x${src.second} out=${msDevice.inputRes.width}x${msDevice.inputRes.height} " +
                    "clusterSrc=${clusterSrcW}x${clusterSrcH} stretch=${device?.stretch} " +
                    "region=${videoDisplayRegion(msDevice.inputRes.width, msDevice.inputRes.height)}"
            )
            val card = renderMediaCard(ctx, src.first, src.second, t, a, albumArt)
            // If the previous toast is still up, keep it so the album-art region can crossfade
            // between the two (the text/background switch to the new card immediately).
            val prev = if (toastOnScreen) synchronized(toastLock) { toastCard } else null
            synchronized(toastLock) {
                toastCard = card
                if (prev != null) {
                    crossfadeFromCard = prev
                    crossfadeStartMs = now
                } else {
                    crossfadeFromCard = null
                }
            }
            val alreadyOnScreen = toastOnScreen
            if (!alreadyOnScreen) {
                toastStartMs = now
            }
            toastUntilMs = now + toastDurationMs
            lastToastTitle = t
            lastToastArtist = a
            lastToastHadArt = hasArt
            AppLog.i(
                "MS9120: now-playing toast ${if (force) "forced" else if (alreadyOnScreen) "updated" else "shown"} " +
                    "for ${toastDurationMs / 1000}s (title len=${t.length}, art=$hasArt)"
            )
        } catch (e: Exception) {
            AppLog.e("MS9120: failed to render now-playing toast: ${e.message}")
        }
    }

    /**
     * User-triggered: immediately re-show the now-playing toast for the track Android Auto
     * currently reports, even if nothing has changed since the last automatic toast.
     * No-op when there is no session, no metadata yet, or the dongle is not attached.
     */
    fun showCurrentToast() {
        val service = AapService.instance ?: return
        val meta = service.lastAaMediaMetadata ?: return
        val t = meta.getSong()
        val a = meta.getArtist()
        val art = if (meta.hasAlbumArt()) meta.getAlbumArt().toByteArray() else null
        if (t.isEmpty() && a.isEmpty()) {
            AppLog.v("MS9120: showCurrentToast: no song/artist yet, ignoring")
            return
        }
        showMediaToast(t, a, art, force = true)
    }

    /**
     * Draw the pill-shaped card as ARGB pixels (transparent outside the pill) at the given frame
     * size. Layout: a circular album-art thumbnail on the left (when [albumArt] is present), then
     * the title (big) and artist (small) left-aligned to the right of it. Returns null if nothing
     * usable was drawn.
     */
    /**
     * The rectangle (in output-frame coords) where the cluster video is actually displayed after
     * the decoder output is scaled into [outW]x[outH] — i.e. honouring the same stretch /
     * scale-to-fit (letterbox) math that [YuvConverter.convert] applies. The toast is laid out and
     * centred on THIS region so it tracks the video instead of the full dongle frame. Falls back
     * to the whole frame when the source resolution is unknown yet (no decoded frame).
     */
    private fun videoDisplayRegion(outW: Int, outH: Int): Rect {
        val srcW = clusterSrcW
        val srcH = clusterSrcH
        if (srcW <= 0 || srcH <= 0 || outW <= 0 || outH <= 0) return Rect(0, 0, outW, outH)
        val stretch = device?.stretch ?: false
        var fitW: Long
        var fitH: Long
        if (stretch) {
            fitW = outW.toLong()
            fitH = outH.toLong()
        } else if (outW.toLong() * srcH <= outH.toLong() * srcW) {
            fitW = outW.toLong()
            fitH = outW.toLong() * srcH / srcW
        } else {
            fitH = outH.toLong()
            fitW = outH.toLong() * srcW / srcH
        }
        fitW = fitW.and(1.inv().toLong())
        fitH = fitH.and(1.inv().toLong())
        val fw = fitW.toInt().coerceIn(2, outW)
        val fh = fitH.toInt().coerceIn(2, outH)
        val offX = (outW - fw) / 2
        val offY = (outH - fh) / 2
        return Rect(offX, offY, offX + fw, offY + fh)
    }

    /**
     * The resolution to render the toast in: the cluster stream's source resolution (so the pill's
     * text/art have natural aspect ratios that then deform with the video). Falls back to the
     * dongle output resolution if no frame has been decoded yet (source unknown), in which case
     * stretch maps it onto the full frame so the toast still fills the display.
     */
    private fun cardSourceRes(msDevice: MS9120Device): Pair<Int, Int> {
        val w = clusterSrcW
        val h = clusterSrcH
        return if (w > 0 && h > 0) Pair(w, h)
        else Pair(msDevice.inputRes.width, msDevice.inputRes.height)
    }

    /**
     * Renders the now-playing pill into a bitmap the size of the CLUSTER-SOURCE frame (the
     * resolution the cluster video is encoded at, [fullW]x[fullH]). The pill + text + art are laid
     * out with natural aspect ratios in this source space. drawToastOver() then resamples the result
     * into the video display region using the same scale factors as the video, so the toast deforms
     * exactly like the picture (stretched with "Étirer l'image", letterboxed otherwise).
     */
    private fun renderMediaCard(
        ctx: Context, fullW: Int, fullH: Int,
        title: String, artist: String, albumArt: ByteArray?
    ): MediaCard? {
        if (fullW <= 0 || fullH <= 0) return null
        val pad = fullW / 30
        // Card spans the central third-ish of the source frame, comfortably visible.
        val cw = fullW - pad * 2
        val ch = fullH / 3
        val cl = pad
        val ct = (fullH - ch) / 2
        // Pill: corner radius == half the height gives the Android-toast rounded ends.
        val radius = ch / 2f

        // Render into a bitmap the FULL source-frame size; the pill is drawn inside it at (cl, ct).
        // We translate the canvas to the pill origin so all the drawing below stays in pill-local
        // (0,0) coordinates, but the bitmap (and card.pixels) is full-frame — so the art-region
        // bounds returned must be offset by (cl, ct) to index card.pixels directly.
        val bmp = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0) // fully transparent base
        canvas.translate(cl.toFloat(), ct.toFloat())

        val cardPaint = Paint().apply {
            isAntiAlias = true
            color = 0xE6303030.toInt() // dark grey, ~90% opaque
        }
        canvas.drawRoundRect(0f, 0f, cw.toFloat(), ch.toFloat(), radius, radius, cardPaint)

        // ---- Left content: circular album-art thumbnail (or a rounded inset if none) ----
        // Big thumbnail with only a thin border to the card edge (art ~86% of the card height).
        val artSize = (ch * 0.86f).toInt().coerceIn(1, ch)
        val artPad = (ch * 0.07f).toInt()
        val artLeft = artPad
        val artTop = (ch - artSize) / 2
        val artRight = artLeft + artSize
        val artBottom = artTop + artSize

        val decoded = decodeAlbumArtBitmap(albumArt, artSize)
        if (decoded != null) {
            val tile = circleCrop(decoded, artSize)
            canvas.drawBitmap(tile, artLeft.toFloat(), artTop.toFloat(), null)
            tile.recycle()
        } else {
            // No art: a subtle ring so the layout still reads as a "media" row.
            val ringPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = (ch * 0.03f).coerceAtLeast(1.5f)
                color = 0x4DFFFFFF // white, ~30%
            }
            canvas.drawCircle(
                (artLeft + artRight) / 2f, (artTop + artBottom) / 2f, artSize / 2f, ringPaint
            )
        }

        // ---- Right content: title (big) + artist (small), left-aligned after the art ----
        val textLeft = artRight + (ch * 0.12f)
        val textRight = cw - artPad - ch * 0.08f
        val textMaxW = (textRight - textLeft).coerceAtLeast(1f)

        val titlePaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
            typeface = Typeface.DEFAULT_BOLD
        }
        titlePaint.textSize = fitTextSize(title, textMaxW, (ch * 0.30f).coerceIn(20f, 90f), 2f, 999f)
        val titleDraw = title.let {
            if (it.length <= 48) it else it.take(48).trimEnd() + "…"
        }

        val artistPaint = Paint().apply {
            isAntiAlias = true
            color = 0xCCFFFFFF.toInt() // white, ~80%
            textAlign = Paint.Align.LEFT
        }
        artistPaint.textSize = fitTextSize(artist, textMaxW, (ch * 0.19f).coerceIn(13f, 46f), 1.5f, 999f)
        val artistDraw = artist.let {
            if (it.isEmpty()) "" else if (it.length <= 56) it else it.take(56).trimEnd() + "…"
        }

        // Vertically centre the two-line block (title above artist) in the card.
        val gap = ch * 0.06f
        val titleH = titlePaint.descent() - titlePaint.ascent()
        val artistH = if (artistDraw.isEmpty()) 0f else artistPaint.descent() - artistPaint.ascent()
        val blockH = titleH + (if (artistDraw.isEmpty()) 0f else gap + artistH)
        var y = (ch - blockH) / 2f
        canvas.drawText(titleDraw, textLeft, y - titlePaint.ascent(), titlePaint)
        y += titleH
        if (artistDraw.isNotEmpty()) {
            y += gap
            canvas.drawText(artistDraw, textLeft, y - artistPaint.ascent(), artistPaint)
        }

        val pixels = IntArray(fullW * fullH)
        bmp.getPixels(pixels, 0, fullW, 0, 0, fullW, fullH)
        bmp.recycle()
        // The art region is defined whether or not there was a photo (it's where the circle —
        // real artwork or the placeholder ring — is drawn), so the crossfade always has the same
        // spot to blend; a "no-art" swap just crossfades the old photo into the new ring. Its bounds
        // are pill-local; offset by (cl, ct) to index the full-frame card.pixels.
        return MediaCard(pixels, cl, ct, cw, ch, artLeft + cl, artTop + ct, artSize, fullW, fullH)
    }

    /**
     * Linear alpha blend of two ARGB pixels: [a] weighted by [pct]/100, [b] by (100-pct)/100,
     * returning a new ARGB. Used to crossfade the album-art region between the previous and new
     * cards. A "no-art" side is fine: its interior is transparent (alpha 0) with only a faint ring,
     * so blending a photo toward a no-art card fades the artwork out into that ring naturally.
     */
    private fun blendArgb(a: Int, b: Int, pct: Int): Int {
        if (pct >= 100) return a
        if (pct <= 0) return b
        val q = 100 - pct
        // ARGB: 0xAARRGGBB. Extract each channel, blend, repack to the SAME slot.
        val aA = (a ushr 24) and 0xFF; val bA = (b ushr 24) and 0xFF // alpha
        val aR = (a ushr 16) and 0xFF; val bR = (b ushr 16) and 0xFF // red
        val aG = (a ushr 8) and 0xFF;  val bG = (b ushr 8) and 0xFF  // green
        val aB = a and 0xFF;           val bB = b and 0xFF           // blue
        val nA = (aA * pct + bA * q) / 100
        val nR = (aR * pct + bR * q) / 100
        val nG = (aG * pct + bG * q) / 100
        val nB = (aB * pct + bB * q) / 100
        return (nA shl 24) or (nR shl 16) or (nG shl 8) or nB
    }

    /**
     * Decode [bytes] (JPEG/PNG album art from the AAP metadata) into a Bitmap at most [maxDim] on
     * the long edge. Returns null if absent or undecodable.
     */
    private fun decodeAlbumArtBitmap(bytes: ByteArray?, maxDim: Int): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            var sample = 1
            while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
            opts.inJustDecodeBounds = false
            opts.inSampleSize = sample
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Crop [bmp] to its centre square and render it as a circle of [diameter] px (transparent
     * outside the circle). The result is a new Bitmap the caller must recycle.
     *
     * Uses clipPath + drawBitmap(src, dst) rather than a BitmapShader because a BitmapShader is
     * sampled in canvas user space — it does NOT auto-scale the source bitmap to the drawing op's
     * bounding box, so the parts of the circle beyond the bitmap's edge get clamped edge pixels
     * (visually: the square "stretched" into a circle). drawBitmap(src=centreSquare, dst=full)
     * scales properly, then the circular clip trims the corners.
     */
    private fun circleCrop(bmp: Bitmap, diameter: Int): Bitmap {
        val side = minOf(bmp.width, bmp.height)
        val sx = (bmp.width - side) / 2
        val sy = (bmp.height - side) / 2
        val out = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply { isAntiAlias = true }
        val path = Path().apply {
            addCircle(diameter / 2f, diameter / 2f, diameter / 2f, Path.Direction.CW)
        }
        canvas.clipPath(path)
        val src = Rect(sx, sy, sx + side, sy + side)
        val dst = RectF(0f, 0f, diameter.toFloat(), diameter.toFloat())
        canvas.drawBitmap(bmp, src, dst, paint)
        return out
    }

    /**
     * Shrink a text's font size until it fits [maxWidth], starting at [initial] and never
     * going below [min]. Uses TextUtils.ellipsize semantics by simply testing the exact drawn
     * width — the string is truncated separately in [renderMediaCard] as a hard cap.
     */
    private fun fitTextSize(text: String, maxWidth: Float, initial: Float, min: Float, max: Float): Float {
        if (text.isEmpty() || maxWidth <= 0f) return min
        val p = Paint()
        p.isAntiAlias = true
        p.typeface = Typeface.DEFAULT_BOLD
        var size = initial.coerceIn(min, max)
        p.textSize = size
        var guard = 0
        while (p.measureText(text) > maxWidth && size > min && guard < 32) {
            size *= 0.94f
            p.textSize = size
            guard++
        }
        return size.coerceIn(min, max)
    }

    /**
     * Records the arrival of a now-playing metadata event (title/artist). Called from the AAP
     * metadata thread so we can measure how long after AA connect the phone's first now-playing
     * arrives. No-ops the state; showMediaToast() still does the dedup + rendering.
     */
    @Synchronized
    fun onMediaMetadataReceived(title: String, artist: String) {
        val now = SystemClock.elapsedRealtime()
        val n = ++mediaMetaCount
        if (firstMetaElapsedMs < 0 && pipelineStartTimeMs > 0) {
            firstMetaElapsedMs = now
            AppLog.i(
                "MS9120: first now-playing metadata ${now - pipelineStartTimeMs}ms after AA connect " +
                    "(title='${title.take(40)}')"
            )
        } else if (n <= 5) {
            AppLog.i("MS9120: now-playing metadata #$n '${title.take(30)}' / '${artist.take(30)}'")
        }
    }

    @Synchronized
    fun onFrame(data: ByteArray, offset: Int, length: Int) {
        if (!running || length <= 0 || offset + length > data.size) return
        val msDevice = device ?: return
        unitsIn++
        logInputRate()

        val unit = data.copyOfRange(offset, offset + length)
        ensureDecoder(msDevice, unit)

        if (!codecReady) {
            checkKeyframeWatchdog()
            return
        }

        val dec = decoder ?: return
        var inIdx = -1
        var retries = 0
        while (inIdx < 0 && retries < 10) {
            inIdx = try {
                dec.dequeueInputBuffer(20_000L)
            } catch (e: Exception) {
                -1
            }
            if (inIdx < 0) {
                try { Thread.sleep(5) } catch (_: Exception) {}
            }
            retries++
        }
        if (inIdx >= 0) {
            val inputBuffer = dec.getInputBuffer(inIdx)
            if (inputBuffer != null) {
                inputBuffer.clear()
                if (inputBuffer.remaining() < unit.size) {
                    AppLog.w("MS9120: access unit of ${unit.size} bytes exceeds input buffer")
                } else {
                    inputBuffer.put(unit)
                    val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000L
                    dec.queueInputBuffer(inIdx, 0, unit.size, ptsUs, 0)
                }
            }
        }
        drainOutput(msDevice)
    }

    private fun logInputRate() {
        val now = SystemClock.elapsedRealtime()
        if (now - inputLogBaselineMs == 0L) {
            inputLogBaseline = unitsIn
            inputLogBaselineMs = now
            lastInputLogMs = now
            return
        }
        if (now - lastInputLogMs < 3000) return
        val rate = ((unitsIn - inputLogBaseline) * 1000L / (now - inputLogBaselineMs)).toInt()
        AppLog.w("MS9120: input rate=$rate units/s (total $unitsIn)")
        inputLogBaseline = unitsIn
        inputLogBaselineMs = now
        lastInputLogMs = now
    }

    fun resetDecoder() {
        synchronized(this) {
            codecReady = false
            lastDecodedFrameMs = 0L
            releaseDecoder()
            synchronized(frameLock) { pendingFrame = null }
            AppLog.i("MS9120: decoder reset for new stream session")
        }
    }

    private fun checkKeyframeWatchdog() {
        if (codecReady) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyframeRequestMs > 800L) {
            lastKeyframeRequestMs = now
            AppLog.w("MS9120: Waiting for initial SPS/Keyframe, requesting cluster keyframe...")
            try {
                onKeyframeNeeded?.invoke()
            } catch (e: Exception) {
                AppLog.w("MS9120: onKeyframeNeeded error: ${e.message}")
            }
        }
    }

    private fun drainOutput(msDevice: MS9120Device) {
        val dec = decoder ?: return
        val reader = imageReader ?: return
        while (running) {
            val outIdx = try {
                dec.dequeueOutputBuffer(bufferInfo, 0)
            } catch (e: Exception) {
                -1
            }
            if (outIdx < 0) return
            if (bufferInfo.size >= 0 && !codecIsEos(bufferInfo)) {
                outFrames++
                try {
                    dec.releaseOutputBuffer(outIdx, true)
                } catch (_: Exception) {}

                val image: Image? = try {
                    reader.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }
                if (image != null) {
                    if (!imgDimsLogged) {
                        imgDimsLogged = true
                        AppLog.i(
                            "MS9120: decoded frame image=${image.width}x${image.height} " +
                                "clusterSrc=${clusterSrcW}x${clusterSrcH} " +
                                "out=${msDevice.inputRes.width}x${msDevice.inputRes.height} " +
                                "stretch=${msDevice.stretch}"
                        )
                    }
                    try {
                        YuvConverter.convert(
                            image,
                            msDevice.wireFormat,
                            frameBuffer,
                            msDevice.inputRes.width,
                            msDevice.inputRes.height,
                            msDevice.stretch
                        )
                        lastDecodedFrameMs = SystemClock.elapsedRealtime()
                        synchronized(frameLock) {
                            pendingFrame = frameBuffer.copyOf()
                        }
                    } finally {
                        image.close()
                    }
                } else {
                    imageNulls++
                }
                maybeLogPipelineDiag()
            } else {
                try {
                    dec.releaseOutputBuffer(outIdx, false)
                } catch (_: Exception) {}
            }
            if (codecIsEos(bufferInfo)) return
        }
    }

    private fun maybeLogPipelineDiag() {
        if (outFrames < 5) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastImageDiagMs < 3000) return
        lastImageDiagMs = now
        AppLog.w(
            "MS9120: outFrames=$outFrames, getOutputImage() null on $imageNulls, " +
                "converted+sent=$framesSent, sendFrame() failed on $sendFails"
        )
    }

    private fun maybeLogSendRate(lastSendMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - sendLogBaselineMs == 0L) {
            sendLogBaseline = framesSent
            sendLogBaselineMs = now
            lastSendLogMs = now
            return
        }
        if (now - lastSendLogMs < 3000) return
        val rate = ((framesSent - sendLogBaseline) * 1000L / (now - sendLogBaselineMs)).toInt()
        lastSendMsAvg = ((lastSendMsAvg + lastSendMs) / 2).toInt()
        AppLog.w(
            "MS9120: send rate=$rate frames/s (total $framesSent), last sendFrame ~${lastSendMs}ms"
        )
        sendLogBaseline = framesSent
        sendLogBaselineMs = now
        lastSendLogMs = now
    }

    private fun codecIsEos(info: MediaCodec.BufferInfo): Boolean =
        (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

    private class ParsedSets(
        val mime: String,
        val sps: ByteArray,
        val pps: ByteArray?,
        val vps: ByteArray?,
        val width: Int,
        val height: Int,
    )

    private fun ensureDecoder(msDevice: MS9120Device, firstUnit: ByteArray) {
        if (codecReady) return
        synchronized(this) {
            if (codecReady) return

            val sets = scanParameterSets(firstUnit) ?: return

            val (w, h) = if (sets.width in 160..7680 && sets.height in 160..4320) {
                Pair(sets.width, sets.height)
            } else {
                parseResolution(
                    context?.let { Settings(it).clusterVideoResolution } ?: "1280x720"
                )
            }
            clusterSrcW = w
            clusterSrcH = h

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

            val imgReader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 3)
            imageReader = imgReader

            val codec = MediaCodec.createDecoderByType(sets.mime)
            codec.configure(format, imgReader.surface, null, 0)
            codec.start()
            decoder = codec
            codecReady = true
            outFrames = 0L
            imageNulls = 0L
            framesSent = 0L
            sendFails = 0L
            lastImageDiagMs = 0L
            val info = codec.codecInfo
            AppLog.i(
                "MS9120: decoder ready (${sets.mime}, ${w}x${h}) codec='${info.name}' " +
                    "software=${info.isSoftwareOnly} via ImageReader surface"
            )
        }
    }

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

    private fun releaseDecoder() {
        try {
            decoder?.stop()
        } catch (_: Exception) {}
        try {
            decoder?.release()
        } catch (_: Exception) {}
        decoder = null

        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null
    }

    private fun renderIdleFrame(
        ctx: Context, w: Int, h: Int, text: String, format: WireFormat
    ): ByteArray {
        val out = ByteArray(w * h * format.bytesPerPixel)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(10, 10, 10))
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = (h / 14f).coerceIn(24f, 72f)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
        val y = h / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, w / 2f, y, paint)

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()

        when (format) {
            WireFormat.RGB888 -> {
                var dst = 0
                for (c in pixels) {
                    out[dst++] = (c and 0xFF).toByte()
                    out[dst++] = ((c shr 8) and 0xFF).toByte()
                    out[dst++] = ((c shr 16) and 0xFF).toByte()
                }
            }
            WireFormat.YUV422 -> {
                // MS9120 UYVY order: [U, Y0, V, Y1]
                var dst = 0
                var i = 0
                while (i < pixels.size) {
                    val c0 = pixels[i]
                    val c1 = pixels[i + 1]
                    val lum0 = ((c0 and 0xFF) * 29 + ((c0 shr 8) and 0xFF) * 150 + ((c0 shr 16) and 0xFF) * 77) shr 8
                    val lum1 = ((c1 and 0xFF) * 29 + ((c1 shr 8) and 0xFF) * 150 + ((c1 shr 16) and 0xFF) * 77) shr 8
                    val y0 = if (lum0 > 80) 235 else 16
                    val y1 = if (lum1 > 80) 235 else 16
                    out[dst++] = 128.toByte() // U neutral
                    out[dst++] = y0.toByte()  // Y0 luminance
                    out[dst++] = 128.toByte() // V neutral
                    out[dst++] = y1.toByte()  // Y1 luminance
                    i += 2
                }
            }
            WireFormat.RGB565 -> {
                var dst = 0
                for (c in pixels) {
                    val r = ((c shr 16) and 0xFF) shr 3
                    val g = ((c shr 8) and 0xFF) shr 2
                    val b = (c and 0xFF) shr 3
                    val rgb565 = (r shl 11) or (g shl 5) or b
                    out[dst++] = (rgb565 and 0xFF).toByte()
                    out[dst++] = ((rgb565 shr 8) and 0xFF).toByte()
                }
            }
        }
        return out
    }

    private fun parseResolution(str: String): Pair<Int, Int> {
        val parts = str.split("x")
        return if (parts.size == 2) {
            val w = parts[0].trim().toIntOrNull() ?: 1280
            val h = parts[1].trim().toIntOrNull() ?: 720
            Pair(w, h)
        } else {
            Pair(1280, 720)
        }
    }

    private fun toInputRes(r: Settings.Ms9120Resolution): InputRes = when (r) {
        Settings.Ms9120Resolution.RES_720P -> InputRes.RES_720P
        Settings.Ms9120Resolution.RES_480P -> InputRes.RES_480P
        Settings.Ms9120Resolution.RES_576P -> InputRes.RES_576P
        Settings.Ms9120Resolution.RES_480 -> InputRes.RES_480
        Settings.Ms9120Resolution.RES_SVGA -> InputRes.RES_SVGA
        Settings.Ms9120Resolution.RES_XGA -> InputRes.RES_XGA
        Settings.Ms9120Resolution.RES_768 -> InputRes.RES_768
        Settings.Ms9120Resolution.RES_HDPLUS -> InputRes.RES_HDPLUS
        Settings.Ms9120Resolution.RES_WUXGA -> InputRes.RES_WUXGA
        Settings.Ms9120Resolution.RES_1080P -> InputRes.RES_1080P
    }

    private fun toWireFormat(c: Settings.Ms9120ColorFormat): WireFormat = when (c) {
        Settings.Ms9120ColorFormat.RGB888 -> WireFormat.RGB888
        Settings.Ms9120ColorFormat.YUV422 -> WireFormat.YUV422
        Settings.Ms9120ColorFormat.RGB565 -> WireFormat.RGB565
    }
}
