package com.we.meet.feature.assistant.aicall.rtc

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.sqrt

/** One upright sRGB JPEG shared by local viewing, history and inference. */
internal object CallPhotoImage {
    const val MAX_BYTES = 512_000 // Existing production API contract.
    const val MAX_SIDE = 1920
    const val MAX_PIXELS = 2_100_000
    const val QUALITY = 92

    fun encode(raw: ByteArray): ByteArray {
        // Native decoding applies EXIF orientation; re-encoding bakes it into pixels
        // and strips private camera metadata. Never keep a separate local original.
        var bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(raw))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val scale = min(1.0, min(MAX_SIDE.toDouble() / maxOf(info.size.width, info.size.height),
                sqrt(MAX_PIXELS.toDouble() / (info.size.width.toLong() * info.size.height))))
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
        }
        try {
            while (true) {
                val output = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, output))
                if (output.size() <= MAX_BYTES) {
                    Log.i("CallStillPhoto", "sharedJpeg=${bitmap.width}x${bitmap.height} bytes=${output.size()} quality=$QUALITY")
                    return output.toByteArray()
                }
                // Reduce dimensions at fixed quality instead of quality 70/55/40.
                val ratio = min(0.9, sqrt(MAX_BYTES.toDouble() / output.size()) * 0.95)
                val smaller = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()), true)
                check(smaller !== bitmap) { "Photo exceeds upload limit" }
                bitmap.recycle(); bitmap = smaller
            }
        } finally { bitmap.recycle() }
    }
}
