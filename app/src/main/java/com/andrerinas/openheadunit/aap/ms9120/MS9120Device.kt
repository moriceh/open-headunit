package com.andrerinas.openheadunit.aap.ms9120

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.andrerinas.openheadunit.utils.AppLog

/**
 * USB driver for the MacroSilicon MS9120 / MS912x / MS9132 controller.
 *
 * Protocol (recovered from the official app and the open-source Linux driver):
 *
 * - Every command is an 8-byte HID feature report on control endpoint 0:
 *       bmRequestType = 0x21 (OUT | CLASS | INTERFACE)
 *       bRequest      = SET_REPORT (9)  / GET_REPORT (1) for reads
 *       wValue        = 0x0300 (Feature report, ID 0)
 *       wIndex        = 0
 * - Video commands 0xA6:
 *       0x00 trigger frame   0x01 video in info  0x02 video out info (VIC)
 *       0x03 trans mode      0x04 transfer on/off 0x05 video on/off   0x07 power on/off
 * - XDATA register access: 0xB6 (write) / 0xB5 (read, then GET_REPORT).
 * - Video frames: pixels pushed over the Bulk OUT endpoint 0x04 via
 *   UsbDeviceConnection.bulkTransfer(), in 0x4000 chunks, preceded by the frame switch and
 *   followed by a zero-length packet (ZLP). No clearhalt or trigger_frame: this is the exact
 *   behaviour of the useThread=0 path of the official app (100% Java, no libmsusb.so).
 */

/**
 * Pixel format sent over the bulk wire.
 *
 * - [RGB888] : 3 bytes/pixel, maximum quality, reliable. The chip reads the wire in **BGR** order.
 * - [YUV422] : 2 bytes/pixel, ~33% fewer bytes => more FPS. The format the official app uses.
 * - [RGB565] : 2 bytes/pixel, ~33% fewer bytes. **Experimental**: on this chip the firmware
 *   forces a fallback to RGB888 (tested: blurry / truncated image).
 *
 * The [code] value is the *wire* colorspace (low nibble of the color byte of `set_video_in`),
 * as defined by the official app (Util.COLORSPACE: RGB565=0, RGB888=1, YUV422=2).
 */
enum class WireFormat(val code: Int, val bytesPerPixel: Int) {
    RGB888(1, 3),
    YUV422(2, 2),
    RGB565(0, 2)
}

/**
 * Source-frame resolution sent over the wire.
 *
 * IMPORTANT: the MS91xx chip DOES NOT scale the frame. The official app gives `set_video_out`
 * the SAME resolution as `set_video_in` (and a matching VIC). If input and output differ, the
 * chip places the frame in a corner of the output canvas and the rest is corrupted.
 *
 * The HDMI output resolution is therefore chosen from the standard modes the chip's timing table
 * recognises (the ones that have a VIC). The smaller, the fewer bytes per frame => more FPS
 * (the bottleneck is USB 2.0 bandwidth). The monitor then runs in that output mode.
 *
 * [vic] = output VESA/CEA number.
 */
enum class InputRes(val width: Int, val height: Int, val vic: Int) {
    RES_720P(1280, 720, 79),
    RES_480P(720, 480, 2),
    RES_576P(720, 576, 17),
    RES_480(640, 480, 64),
    RES_SVGA(800, 600, 66),
    RES_XGA(1024, 768, 71),
    RES_768(1280, 768, 84),
    RES_HDPLUS(1366, 768, 102),
    RES_WUXGA(1680, 1050, 120),
    RES_1080P(1920, 1080, 129)
}

class MS9120Device(
    private val usbManager: UsbManager,
    val device: UsbDevice
) {
    companion object {
        const val BULK_CHUNK_SIZE = 16384          // 0x4000, same as the official app
        const val BULK_ENDPOINT_ADDRESS = 0x04
        const val BULK_TIMEOUT_MS = 1000           // 0x3e8, the official app's bulk timeout

        init {
            // The bulk USB goes through UsbDeviceConnection.bulkTransfer() (framework);
            // only the YUV converter stays native.
            try { System.loadLibrary("yuv_converter") } catch (_: Exception) {}
        }

        // HID control-transfer parameters
        private const val REQ_SET_REPORT = 0x09
        private const val REQ_GET_REPORT = 0x01
        private const val REPORT_VALUE = 0x0300
        private const val REPORT_INDEX = 0
        private const val REPORT_TIMEOUT_MS = 1000

        // HID operations (first byte of the report)
        private const val OP_VIDEO: Byte = 0xA6.toByte()
        private const val OP_READ_XDATA: Byte = 0xB5.toByte()
        private const val OP_WRITE_XDATA: Byte = 0xB6.toByte()
        private const val OP_WRITE_TWO_BYTES: Byte = 0x12

        // 0xA6 sub-operations
        private const val SUBOP_TRIGGER = 0x00
        private const val SUBOP_VIDEO_IN = 0x01
        private const val SUBOP_VIDEO_OUT = 0x02
        private const val SUBOP_TRANS_MODE = 0x03
        private const val SUBOP_TRANSFER = 0x04
        private const val SUBOP_VIDEO_ENABLE = 0x05
        private const val SUBOP_POWER = 0x07

        // XDATA registers
        private const val XDATA_SDRAM_TYPE = 0x0030
        private const val XDATA_VIDEO_PORT = 0x0031
        private const val XDATA_HPD = 0x0032
        private const val XDATA_DISPLAY_COLORSPACE = 0x0033

        // Video ports (Util.VIDEO_PORT of the official app)
        const val PORT_VGA = 2
        const val PORT_HDMI = 5
        const val PORT_DIGITAL = 6
        private const val XDATA_POWER_STATE = 0xC620      // official app: xdata_read(50720)
        private const val XDATA_POWER_ON_SUCCESS = 0xC454 // official app: xdata_read(50260)
        // Video-OUTPUT un-mute register.
        // Official app (MsDisplay.set_video_mute), INDEPENDENT of the port:
        //   mute   : xdata_modBits(0xF004, 0x00, 0x80)  -> clears bit 0x80
        //   unmute : xdata_modBits(0xF004, 0x80, 0x80)  -> sets  bit 0x80
        // This is the bit that makes the monitor see (or not) a signal.
        private const val XDATA_MUTE = 0xF004
        private const val MUTE_MASK = 0x80
        private const val XDATA_ANDROID_HPD_1 = 0xDEEE    // official app: xdata_write(57070, 1)
        private const val XDATA_ANDROID_HPD_2 = 0xF600    // official app: xdata_modBits(62976, 0, 1)
        private const val XDATA_FRAME_SWITCH = 0xF1E2
        private const val XDATA_FRAME_TRANSFER_SWITCH = 0xF202

        // SDRAM types
        const val SDRAM_NONE = 4

        // Output VESA VIC for 1280x720@60 (same as the Linux driver)
        const val VIC_720P60 = 79
    }

    private var connection: UsbDeviceConnection? = null
    private var bulkEndpoint: UsbEndpoint? = null
    private val claimedInterfaces = mutableListOf<UsbInterface>()
    private var frameId: Byte = 0
    private var unmuted = false

    /** Active wire format. Set before init. */
    var wireFormat: WireFormat = WireFormat.YUV422

    /** Source-frame resolution = HDMI output resolution. */
    var inputRes: InputRes = InputRes.RES_720P

    /**
     * Frame skipping: when true, the pipeline drops decoded frames that arrive too late to keep
     * the video timing, instead of accumulating lag. Result: the video plays at normal speed
     * (dropping frames) even when the USB FPS is low.
     */
    var frameSkip: Boolean = true

    /**
     * Stretch the image over the whole output frame (ignoring the source aspect ratio).
     * When false: scale-to-fit with black bars (letterbox/pillarbox), aspect ratio preserved.
     */
    var stretch: Boolean = false

    /** Size of a full frame in bytes, according to the source resolution and format. */
    val framePixelsBytes: Int
        get() = inputRes.width * inputRes.height * wireFormat.bytesPerPixel

    private fun logDiag(msg: String) {
        AppLog.d("MS9120: $msg")
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            AppLog.w("MS9120: sleep interrupted")
        }
    }

    fun open(): Boolean {
        logDiag("Open VID:0x${Integer.toHexString(device.vendorId)} PID:0x${Integer.toHexString(device.productId)} - ${device.interfaceCount} interface(s)")
        val conn = usbManager.openDevice(device) ?: run {
            logDiag("ERROR: openDevice() returned NULL (missing USB permission or device busy)")
            return false
        }
        connection = conn
        logDiag("USB connection opened (fd=${conn.fileDescriptor})")

        // Activate the USB configuration (required for the endpoints to be operational)
        try {
            val config = device.getConfiguration(0)
            if (config != null) {
                conn.setConfiguration(config)
                logDiag("USB configuration #0 enabled (${config.interfaceCount} ifaces)")
            } else {
                logDiag("WARNING: no USB configuration found")
            }
        } catch (e: Exception) {
            logDiag("WARNING: setConfiguration failed: ${e.message}")
        }

        var fallback: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val claimed = conn.claimInterface(iface, true)
            logDiag("Interface #$i (ID=${iface.id}, class=${iface.interfaceClass}, sub=${iface.interfaceSubclass}, eps=${iface.endpointCount}) -> claimed=$claimed")
            if (claimed) {
                claimedInterfaces.add(iface)
            }
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                val dir = if (ep.direction == UsbConstants.USB_DIR_OUT) "OUT" else "IN"
                logDiag("  - EP #$e : addr=0x${Integer.toHexString(ep.address)} dir=$dir type=${ep.type} maxPacket=${ep.maxPacketSize}")
                val isBulkOut = ep.direction == UsbConstants.USB_DIR_OUT &&
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                if (isBulkOut) {
                    if (ep.address == BULK_ENDPOINT_ADDRESS) {
                        bulkEndpoint = ep
                        logDiag("  >>> Bulk OUT endpoint 0x04 selected (interface #$i)")
                    } else if (fallback == null) {
                        fallback = ep
                    }
                }
            }
        }
        if (bulkEndpoint == null) {
            bulkEndpoint = fallback
            if (bulkEndpoint != null) {
                logDiag("  >>> Warning: 0x04 not found, using Bulk OUT endpoint 0x${Integer.toHexString(bulkEndpoint!!.address)} instead")
            }
        }
        if (bulkEndpoint == null) {
            logDiag("ERROR: no Bulk OUT endpoint found!")
            close()
            return false
        }

        logDiag("USB initialisation OK")
        return true
    }

    // ------------------------------------------------------------------
    // HID control transfers
    // ------------------------------------------------------------------

    private fun hidSet(data: ByteArray): Int {
        val conn = connection ?: return -1
        return conn.controlTransfer(
            0x21, REQ_SET_REPORT, REPORT_VALUE, REPORT_INDEX,
            data, data.size, REPORT_TIMEOUT_MS
        )
    }

    private fun hidGet(data: ByteArray): Int {
        val conn = connection ?: return -1
        return conn.controlTransfer(
            0xA1, REQ_GET_REPORT, REPORT_VALUE, REPORT_INDEX,
            data, data.size, REPORT_TIMEOUT_MS
        )
    }

    // ------------------------------------------------------------------
    // XDATA register access
    // ------------------------------------------------------------------

    /** Reads one byte from the XDATA bus. Returns 0..255 or -1 on error. */
    fun xdataRead(addr: Int): Int {
        val buf = byteArrayOf(OP_READ_XDATA, (addr shr 8).toByte(), addr.toByte(), 0, 0, 0, 0, 0)
        if (hidSet(buf) < 0) return -1
        if (hidGet(buf) < 0) return -1
        val value = buf[3].toInt() and 0xFF
        logDiag("xdataRead(0x%04X) = 0x%02X".format(addr, value))
        return value
    }

    /** Writes one byte to the XDATA bus. */
    fun xdataWrite(addr: Int, value: Int, extra: Int = 0) {
        val buf = byteArrayOf(
            OP_WRITE_XDATA, (addr shr 8).toByte(), addr.toByte(),
            value.toByte(), extra.toByte(), 0, 0, 0
        )
        val ret = hidSet(buf)
        logDiag("xdataWrite(0x%04X) = 0x%02X -> ret=$ret".format(addr, value))
    }

    fun xdataModBits(addr: Int, value: Int, mask: Int) {
        val reg = xdataRead(addr)
        if (reg < 0) return
        xdataWrite(addr, (reg and mask.inv()) or (value and mask))
    }

    // ------------------------------------------------------------------
    // Video commands (0xA6 reports)
    // ------------------------------------------------------------------

    private fun videoCmd(subOp: Int, b2: Int = 0, b3: Int = 0, b4: Int = 0, b5: Int = 0, b6: Int = 0, b7: Int = 0) {
        val ret = hidSet(byteArrayOf(OP_VIDEO, subOp.toByte(), b2.toByte(), b3.toByte(), b4.toByte(), b5.toByte(), b6.toByte(), b7.toByte()))
        logDiag("videoCmd(0x%02X [%02X %02X %02X %02X %02X %02X]) -> ret=$ret".format(subOp, b2, b3, b4, b5, b6, b7))
    }

    fun setTransfer(enable: Boolean) = videoCmd(SUBOP_TRANSFER, if (enable) 1 else 0)

    fun setTransferModeFrame() = videoCmd(SUBOP_TRANS_MODE, 0) // FRAME mode = 0

    /**
     * Reports the input frame: [op, sub, wHi, wLo, hHi, hLo, color, byteSel].
     * color = (memColor<<4)|wireColor, widths aligned to 4.
     */
    fun setVideoIn(width: Int, height: Int, color: Int, byteSel: Int = 0) =
        videoCmd(SUBOP_VIDEO_IN, width shr 8, width and 0xFF, height shr 8, height and 0xFF, color, byteSel)

    /** Reports the output frame: index = VIC, then output size. */
    fun setVideoOut(vic: Int, color: Int, width: Int, height: Int) =
        videoCmd(SUBOP_VIDEO_OUT, vic, color, width shr 8, width and 0xFF, height shr 8, height and 0xFF)

    fun setVideoEnable(enable: Boolean) = videoCmd(SUBOP_VIDEO_ENABLE, if (enable) 1 else 0)

    fun setPowerOn(on: Boolean) = videoCmd(SUBOP_POWER, if (on) 1 else 0, if (on) 2 else 0)

    fun triggerFrame(index: Int, delay: Int = 100) = videoCmd(SUBOP_TRIGGER, index, delay)

    /**
     * MS9120 frame switch (official app, MsDisplay.frame_transfer_switch).
     * 1) HID command 0x12: [0x12, 0xF2, 0x02, 0, id, 0, 0, 0]
     * 2) xdata_write_switch(0xF202=61954, 1) with the flag b[4]=1 (special address).
     */
    fun frameSwitch(id: Byte) {
        val ret = hidSet(byteArrayOf(OP_WRITE_TWO_BYTES, 0xF2.toByte(), 0x02, 0, id, 0, 0, 0))
        logDiag("frameSwitch(id=$id) -> ret=$ret")
        xdataWrite(XDATA_FRAME_TRANSFER_SWITCH, 1, extra = 1)
    }

    /**
     * Toggles the video OUTPUT on/off — exact replica of the official app's
     * [MsDisplay.set_video_mute] (independent of the port):
     *   mute   : 0xF004, clear bit 0x80  (xdata_modBits, 0, 0x80)
     *   unmute : 0xF004, set   bit 0x80  (xdata_modBits, 0x80, 0x80)
     */
    fun setVideoMute(mute: Boolean) {
        if (mute) xdataModBits(XDATA_MUTE, 0, MUTE_MASK)
        else      xdataModBits(XDATA_MUTE, MUTE_MASK, MUTE_MASK)
    }

    fun setAndroidHpd() {
        xdataWrite(XDATA_ANDROID_HPD_1, 1)
        xdataModBits(XDATA_ANDROID_HPD_2, 0, 1)
    }

    fun getPowerState(): Int = xdataRead(XDATA_POWER_STATE)
    fun getPowerOnSuccess(): Int = xdataRead(XDATA_POWER_ON_SUCCESS)
    fun getVideoPort(): Int = xdataRead(XDATA_VIDEO_PORT)
    fun getSDRAMType(): Int = xdataRead(XDATA_SDRAM_TYPE)
    fun getHpd(): Int = xdataRead(XDATA_HPD)
    fun getDisplayColorSpace(): Int = xdataRead(XDATA_DISPLAY_COLORSPACE)

    // ------------------------------------------------------------------
    // 720p init sequence (faithful to the official app)
    // ------------------------------------------------------------------

    /**
     * Powers the dongle on and configures the output at the selected resolution/format.
     * Call on a background thread (the sequence has sleeps, ~0.5 s).
     * Returns false if a blocking USB error is detected.
     */
    fun initDisplay(): Boolean {
        logDiag("=== MS9120 initialisation ===")

        val sdram = getSDRAMType()
        val port = getVideoPort()
        val hpd = getHpd()
        logDiag("SDRAM type=$sdram, video port=$port, HPD=$hpd")
        if (hpd == 0) {
            logDiag("WARNING: HPD=0: no HDMI display detected on the dongle side?")
        }

        setTransfer(false)
        sleep(30)

        val powerState = getPowerState()
        logDiag("Power state = $powerState")
        if (powerState < 0) {
            logDiag("ERROR: cannot read the power state (USB communication failing)")
            return false
        }
        if (powerState == 0) {
            setPowerOn(true)
            var ok = false
            var attempts = 0
            for (i in 0 until 10) {
                if (getPowerOnSuccess() == 0) {
                    ok = true
                    break
                }
                sleep(100)
                attempts++
            }
            logDiag("Power ON success = $ok (attempts=$attempts)")
        } else {
            logDiag("Power already on (state=$powerState), set_power_on not needed")
        }

        setVideoMute(true)
        sleep(50)

        // Source-frame size (the chip places it on the output canvas).
        val inW = inputRes.width
        val inH = inputRes.height
        val inW4 = (inW + 3) and 3.inv()   // width aligned to 4, like the official app
        setTransferModeFrame()

        // Packed color field: (memory colorspace << 4) | wire format.
        val memColor = wireFormat.code
        val wireColor = wireFormat.code
        val colorIn = (memColor shl 4) or wireColor
        val colorOut = getDisplayColorSpace()          // register 51 (default 0 on HDMI)
        logDiag("Source: ${inW}x${inH}, format=${wireFormat.name} (${wireFormat.bytesPerPixel} o/px), frame=${framePixelsBytes} o")
        logDiag("Colors: in=0x%02X (mem=%d wire=%d), out=%d".format(colorIn, memColor, wireColor, colorOut))
        // The chip DOES NOT scale: the HDMI output must have the same resolution as the input,
        // otherwise the frame is placed in a corner of the canvas and the rest is corrupted.
        setVideoIn(inW4, inH, colorIn, byteSel = 0)
        setVideoOut(inputRes.vic, colorOut, inputRes.width, inputRes.height)
        setTransfer(true)
        sleep(50)

        setVideoEnable(true)
        setAndroidHpd()
        logDiag("=== Initialisation done (${wireFormat.name}, source ${inW}x${inH}) ===")
        return true
    }

    // ------------------------------------------------------------------
    // Frame send
    // ------------------------------------------------------------------

    /**
     * Sends a raw frame, a faithful replica of the official app's useThread=0 path
     * (CaptureService.BulkSendSplit, 100% Java):
     *
     *   frame_switch(frame_id) -> bulk OUT (frameSize bytes) in 0x4000 chunks -> ZLP
     *
     * This path does NOT call clearhalt or trigger_frame: the chip displays the frame as soon as
     * it is complete (the ZLP marks the end). The frame switch (frame_switch) is what toggles the
     * displayed buffer. The output is un-muted once, on the first frame.
     *
     * Returns the number of bytes sent or -1 on error.
     */
    fun sendFrame(pixels: ByteArray): Int {
        val ep = bulkEndpoint ?: return -1
        val conn = connection ?: return -1
        val frameSize = framePixelsBytes
        if (pixels.size != frameSize) {
            logDiag("ERROR sendFrame: size ${pixels.size} != expected $frameSize (${wireFormat.name})")
            return -1
        }

        // Un-mute the output once, on the first frame (like the official app's init:
        // set_video_mute(false) on the first frame).
        if (!unmuted) {
            setVideoMute(false)
            unmuted = true
        }

        // 1. Frame switch (HID 0x12 + xdata_write_switch 0xF202)
        frameSwitch(frameId)

        // 2. Bulk OUT of the frame (frameSize bytes) in 0x4000 chunks
        val chunk = ByteArray(BULK_CHUNK_SIZE)
        var off = 0
        var sent = 0
        while (off < frameSize) {
            val len = minOf(BULK_CHUNK_SIZE, frameSize - off)
            System.arraycopy(pixels, off, chunk, 0, len)
            val ret = conn.bulkTransfer(ep, chunk, len, BULK_TIMEOUT_MS)
            if (ret < 0) {
                logDiag("ERROR bulkTransfer: ret=$ret at offset $off")
                return -1
            }
            sent += ret
            off += ret
        }

        // 3. ZLP: zero-length packet, frame-end delimiter
        conn.bulkTransfer(ep, ByteArray(0), 0, BULK_TIMEOUT_MS)

        // 4. Toggle the frame id (double buffer)
        frameId = (1 - frameId).toByte()

        return sent
    }

    /** Powers off the video output and the dongle. */
    fun powerOff() {
        try {
            setVideoEnable(false)
            setTransfer(false)
            setPowerOn(false)
            unmuted = false
            logDiag("Power OFF sent")
        } catch (e: Exception) {
            AppLog.w("MS9120: error during powerOff")
        }
    }

    fun close() {
        try {
            powerOff()
            // Do NOT close rawUsbFd manually: that is the Android HAL socket fd, closed
            // automatically by connection.close(). Closing it twice invalidates the
            // UsbDeviceConnection.
            for (iface in claimedInterfaces) {
                connection?.releaseInterface(iface)
            }
            connection?.close()
        } catch (e: Exception) {
            AppLog.e("MS9120: error closing USB")
        } finally {
            claimedInterfaces.clear()
            connection = null
            bulkEndpoint = null
        }
    }
}
