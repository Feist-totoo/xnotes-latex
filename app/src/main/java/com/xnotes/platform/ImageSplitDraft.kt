package com.xnotes.platform

import android.graphics.Bitmap
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.*
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Frozen image and guarded atomic replacement. All raster work runs off the UI thread. */
class ImageSplitDraft(item: ImageItem, private val imageDir: File, val apply: (List<ImageItem>) -> Boolean) {
    private val original = ImageItem(item.image, item.rect, item.orientation, item.angle, item.flip, item.part,
        cutOutline = item.cutOutline?.toList()).also { it.sync = item.sync }
    val outline = ImageSliceGeometry.outline(original)

    fun preview(): Bitmap = raster(1200, 1_500_000)

    private fun raster(maxSide: Int, maxPixels: Int): Bitmap {
        val part = original.part ?: ImageCrop.WHOLE
        val turned = Math.floorMod(original.orientation, 180) == 90
        val nativeW = original.image.width * part.w
        val nativeH = original.image.height * part.h
        val w = if (turned) nativeH else nativeW
        val h = if (turned) nativeW else nativeH
        require(w.isFinite() && h.isFinite() && w > 0 && h > 0)
        val scale = minOf(1.0, maxSide / maxOf(w, h), sqrt(maxPixels / (w * h)))
        val surface = AndroidRasterSurface.create(ceil(w * scale).toInt().coerceAtLeast(1), ceil(h * scale).toInt().coerceAtLeast(1))
        surface.renderer().drawImage(original.image, Rect(0.0, 0.0, surface.bitmap.width.toDouble(), surface.bitmap.height.toDouble()),
            original.orientation, 0.0, original.flip, original.part)
        return surface.bitmap
    }

    /** Every source pixel belongs to exactly one side, including pixels on the cutting line. */
    fun split(a: Pt, b: Pt): List<ImageItem> {
        val polygons = ImageSliceGeometry.split(outline, a, b) ?: error("Line must cross the image")
        val source = raster(4096, 8_000_000)
        val created = ArrayList<ImageItem>()
        imageDir.mkdirs()
        try {
            for ((index, polygon) in polygons.withIndex()) {
                val bounds = Rect.bounding(polygon)
                val left = floor(bounds.left * source.width).toInt().coerceIn(0, source.width - 1)
                val top = floor(bounds.top * source.height).toInt().coerceIn(0, source.height - 1)
                val right = ceil(bounds.right * source.width).toInt().coerceIn(left + 1, source.width)
                val bottom = ceil(bounds.bottom * source.height).toInt().coerceIn(top + 1, source.height)
                val width = right - left; val height = bottom - top
                val pixels = IntArray(width * height)
                source.getPixels(pixels, 0, width, left, top, width, height)
                var visible = false
                val dx = b.x - a.x; val dy = b.y - a.y
                for (y in 0 until height) for (x in 0 until width) {
                    val side = dx * ((top + y + .5) / source.height - a.y) - dy * ((left + x + .5) / source.width - a.x)
                    val offset = y * width + x
                    if ((side >= 0) != (index == 0)) pixels[offset] = 0
                    if (pixels[offset] ushr 24 != 0) visible = true
                }
                check(visible) { "Cut would create an empty piece" }
                val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                val file = File.createTempFile("image-piece-", ".png", imageDir)
                try {
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    val fraction = Rect(left.toDouble() / source.width, top.toDouble() / source.height,
                        width.toDouble() / source.width, height.toDouble() / source.height)
                    created.add(ImageItem(ImageData(file, width, height), ImageCrop.croppedRect(original, fraction),
                        angle = original.angle, cutOutline = polygon.map { Pt((it.x - fraction.x) / fraction.w, (it.y - fraction.y) / fraction.h) })
                        .also { it.sync = original.sync })
                } catch (e: Throwable) { file.delete(); throw e }
                finally { bitmap.recycle() }
            }
            return created
        } catch (e: Throwable) {
            created.forEach { it.image.file.delete() }
            throw e
        } finally { source.recycle() }
    }

    companion object {
        fun accepts(items: List<CanvasItem>): Boolean = (items.singleOrNull() as? ImageItem)?.let {
            !it.locked && it.formula == null && it.rect.w > 0 && it.rect.h > 0
        } == true
    }
}
