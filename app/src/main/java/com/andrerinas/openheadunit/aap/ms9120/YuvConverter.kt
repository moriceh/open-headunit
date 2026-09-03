package com.andrerinas.openheadunit.aap.ms9120

import android.media.Image
import com.andrerinas.openheadunit.utils.AppLog
import java.nio.ByteBuffer

/**
 * Converts the Android decoder output (YUV_420_888) into the wire format (BGR888, UYVY or
 * RGB565) at the target [dstWidth]x[dstHeight] expected by the MS91xx chip.
 *
 * Primary path: native C++ library. Scale-to-fit (aspect ratio preserved, the rest black) unless
 * [stretch] is set, in which case the source fills the whole destination.
 */
object YuvConverter {

    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("yuv_converter")
            isNativeLoaded = true
            AppLog.i("MS9120: native yuv_converter library loaded")
        } catch (e: UnsatisfiedLinkError) {
            AppLog.w("MS9120: could not load native lib, Kotlin fallback active")
            isNativeLoaded = false
        }
    }

    /**
     * Converts a YUV420 image into [format] at [dstWidth]x[dstHeight] into [outArray], whose size
     * MUST be dstWidth * dstHeight * format.bytesPerPixel.
     */
    fun convert(
        image: Image,
        format: WireFormat,
        outArray: ByteArray,
        dstWidth: Int,
        dstHeight: Int,
        stretch: Boolean
    ) {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        if (isNativeLoaded && yPlane.buffer.isDirect && uPlane.buffer.isDirect && vPlane.buffer.isDirect) {
            try {
                nativeConvertYuv420To720p(
                    yPlane.buffer,
                    yPlane.rowStride,
                    yPlane.pixelStride,
                    uPlane.buffer,
                    vPlane.buffer,
                    uPlane.rowStride,
                    uPlane.pixelStride,
                    image.width,
                    image.height,
                    dstWidth,
                    dstHeight,
                    format.code,
                    stretch,
                    outArray
                )
                return
            } catch (e: Exception) {
                AppLog.e("MS9120: native conversion failed, using Kotlin fallback")
            }
        }

        convertKotlin(image, format, outArray, dstWidth, dstHeight, stretch)
    }

    private external fun nativeConvertYuv420To720p(
        yBuffer: ByteBuffer,
        yRowStride: Int,
        yPixelStride: Int,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        uvRowStride: Int,
        uvPixelStride: Int,
        imgWidth: Int,
        imgHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        format: Int,
        stretch: Boolean,
        outArray: ByteArray
    )

    /** Kotlin fallback (when the native lib is unavailable). */
    private fun convertKotlin(
        image: Image, format: WireFormat, out: ByteArray,
        dstWidth: Int, dstHeight: Int, stretch: Boolean
    ) {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride

        val imgWidth = image.width
        val imgHeight = image.height
        val bpp = format.bytesPerPixel

        if (format == WireFormat.YUV422) {
            var i = 0
            while (i + 3 < out.size) {
                out[i]     = 128.toByte() // U
                out[i + 1] = 16.toByte()  // Y0
                out[i + 2] = 128.toByte() // V
                out[i + 3] = 16.toByte()  // Y1
                i += 4
            }
        } else {
            for (i in out.indices) out[i] = 0
        }

        var fitW: Long = 0
        var fitH: Long = 0
        if (stretch) {
            fitW = dstWidth.toLong()
            fitH = dstHeight.toLong()
        } else if (dstWidth.toLong() * imgHeight <= dstHeight.toLong() * imgWidth) {
            fitW = dstWidth.toLong()
            fitH = dstWidth.toLong() * imgHeight / imgWidth
        } else {
            fitH = dstHeight.toLong()
            fitW = dstHeight.toLong() * imgWidth / imgHeight
        }
        fitW = fitW.and(1.inv().toLong())
        fitH = fitH.and(1.inv().toLong())
        if (fitW < 2) fitW = 2
        if (fitH < 2) fitH = 2
        val offX = (dstWidth - fitW.toInt()) / 2
        val offY = (dstHeight - fitH.toInt()) / 2

        if (format == WireFormat.YUV422) {
            // YUV422 (UYVY): 2 o/px, pairs [U, Y0, V, Y1]. The planes are already YUV.
            var yy = offY
            while (yy < offY + fitH.toInt()) {
                val srcY = ((yy - offY) * imgHeight / fitH.toInt()).coerceAtMost(imgHeight - 1)
                val yRowStart = srcY * yRowStride
                val uvRowStart = (srcY shr 1) * uRowStride
                val uvRowStartV = (srcY shr 1) * vRowStride
                var xx = offX
                while (xx + 1 < offX + fitW.toInt()) {
                    val sxa = ((xx - offX) * imgWidth / fitW.toInt()).coerceAtMost(imgWidth - 1)
                    val sxb = ((xx + 1 - offX) * imgWidth / fitW.toInt()).coerceAtMost(imgWidth - 1)
                    val o = (yy * dstWidth + xx) * 2
                    out[o]     = uBuffer.get(uvRowStart + (sxa shr 1) * uPixelStride)
                    out[o + 1] = yBuffer.get(yRowStart + sxa * yPixelStride)
                    out[o + 2] = vBuffer.get(uvRowStartV + (sxa shr 1) * vPixelStride)
                    out[o + 3] = yBuffer.get(yRowStart + sxb * yPixelStride)
                    xx += 2
                }
                yy++
            }
            return
        }

        var y = offY
        while (y < offY + fitH.toInt()) {
            var srcY = ((y - offY) * imgHeight / fitH.toInt())
            if (srcY > imgHeight - 1) srcY = imgHeight - 1
            val yRowStart = srcY * yRowStride
            val uvRowStart = (srcY shr 1) * uRowStride
            val uvRowStartV = (srcY shr 1) * vRowStride

            var x = offX
            while (x < offX + fitW.toInt()) {
                var srcX = ((x - offX) * imgWidth / fitW.toInt())
                if (srcX > imgWidth - 1) srcX = imgWidth - 1

                val yc = yBuffer.get(yRowStart + srcX * yPixelStride).toInt() and 0xFF - 16
                val uc = uBuffer.get(uvRowStart + (srcX shr 1) * uPixelStride).toInt() and 0xFF - 128
                val vc = vBuffer.get(uvRowStartV + (srcX shr 1) * vPixelStride).toInt() and 0xFF - 128

                // BT.601 limited-range coefficients, fixed point /4096 (>>12).
                var r = (4771 * yc + 6538 * vc + 2048) shr 12
                var g = (4771 * yc - 1605 * uc - 3332 * vc + 2048) shr 12
                var b = (4771 * yc + 8264 * uc + 2048) shr 12
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                val o = (y * dstWidth + x) * bpp
                if (format == WireFormat.RGB888) {
                    // The chip reads the wire as BGR (not RGB).
                    out[o] = b.toByte()
                    out[o + 1] = g.toByte()
                    out[o + 2] = r.toByte()
                } else {
                    // RGB565: 5-6-5 packed, little-endian (low byte first).
                    val p565 = (((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)).toShort()
                    out[o] = (p565.toInt() and 0xFF).toByte()
                    out[o + 1] = ((p565.toInt() shr 8) and 0xFF).toByte()
                }
                x++
            }
            y++
        }
    }
}
