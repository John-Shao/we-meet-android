package com.we.meet.ui.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

/** Native JPEG normalization, EXIF orientation and a single high-quality bounded output. */
class CallPhotoEncodingTest {
    private fun encode(raw: ByteArray): ByteArray {
        val type = Class.forName("com.we.meet.feature.assistant.aicall.rtc.CallPhotoImage")
        return type.getDeclaredMethod("encode", ByteArray::class.java).invoke(type.getField("INSTANCE").get(null), raw) as ByteArray
    }

    private fun raw(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)); output.toByteArray()
    }

    @Test fun exifRotationBecomesUprightPixelsWithoutUpscaling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val file = File.createTempFile("photo-orientation", ".jpg", context.cacheDir)
        try {
            file.writeBytes(raw(bitmap))
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val jpeg = encode(file.readBytes())
            val image = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            try {
                assertEquals(64, image.width); assertEquals(128, image.height)
                val pixel = image.getPixel(32, 64)
                assertTrue(Color.red(pixel) > 200); assertTrue(Color.green(pixel) < 60); assertTrue(Color.blue(pixel) < 60)
                assertTrue(jpeg.size <= 512_000)
            } finally { image.recycle() }
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun detailedLargeStillIsBoundedWithoutChangingAspectRatio() {
        val bitmap = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888)
        val random = Random(7)
        val row = IntArray(bitmap.width)
        try {
            repeat(bitmap.height) { y ->
                repeat(row.size) { x -> row[x] = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
                bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
            }
            val jpeg = encode(raw(bitmap))
            val image = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            try {
                assertTrue(jpeg.size <= 512_000)
                assertTrue(maxOf(image.width, image.height) <= 1920)
                assertTrue(image.width.toLong() * image.height <= 2_100_000)
                assertEquals(4.0 / 3, image.width.toDouble() / image.height, 0.005)
            } finally { image.recycle() }
        } finally { bitmap.recycle() }
    }
}