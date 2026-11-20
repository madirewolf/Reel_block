package com.reelblock.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import java.io.File

/**
 * How the block screen looks: a color, a pattern on that color, or a
 * photo. Used by the real cover and by the preview on the main screen,
 * so the two always match.
 */
object CoverLook {

    /** Colors the picker offers. 0 = the app's own background. */
    val SWATCHES = intArrayOf(
        0,
        0xFF1C2533.toInt(), // slate
        0xFF2A1F33.toInt(), // plum
        0xFF1D2B24.toInt(), // pine
        0xFF33231C.toInt(), // clay
        0xFFE9E6DF.toInt(), // paper
    )

    fun baseColor(context: Context, color: Int): Int =
        if (color == 0) context.getColor(R.color.rb_bg) else color

    /** Dark text on light colors, light text on dark ones. */
    fun textColor(base: Int): Int =
        if (ColorUtils.calculateLuminance(base) > 0.45) 0xFF141414.toInt() else 0xFFF2F2F2.toInt()

    fun photoFile(context: Context): File = File(context.filesDir, CoverStyle.PHOTO_FILE)

    fun hasPhoto(context: Context): Boolean = photoFile(context).length() > 0

    /** The saved photo, or null if there is none or it can't be read. */
    fun loadPhoto(context: Context): Bitmap? = try {
        val file = photoFile(context)
        if (file.length() > 0) BitmapFactory.decodeFile(file.path) else null
    } catch (_: Throwable) {
        null
    }

    /**
     * Styles a cover: [root] gets the background, [photoView] the photo
     * (or hidden), [title] a readable text color. [photo] is only used
     * for [CoverStyle.PHOTO]; without one the cover falls back to the color.
     */
    fun apply(
        context: Context,
        root: View,
        photoView: ImageView,
        title: TextView,
        style: String,
        color: Int,
        photo: Bitmap?,
    ) {
        val base = baseColor(context, color)
        val density = context.resources.displayMetrics.density
        root.background = if (style in CoverStyle.PATTERNS) {
            PatternDrawable(base, textColor(base), style, density)
        } else {
            ColorDrawable(base)
        }
        val showPhoto = style == CoverStyle.PHOTO && photo != null
        if (showPhoto) {
            photoView.setImageBitmap(photo)
            photoView.visibility = View.VISIBLE
            title.setTextColor(0xFFFFFFFF.toInt())
            title.setShadowLayer(10f * density, 0f, 0f, 0xCC000000.toInt())
        } else {
            photoView.setImageDrawable(null)
            photoView.visibility = View.GONE
            title.setTextColor(textColor(base))
            title.setShadowLayer(0f, 0f, 0f, 0)
        }
    }
}

/**
 * A quiet repeating pattern (dots, diagonal stripes or a grid) on an
 * opaque base color. Drawn from one small tile, so it costs the same at
 * any size.
 */
class PatternDrawable(
    private val base: Int,
    ink: Int,
    pattern: String,
    density: Float,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Declared before init: initializers run in order.
    private var tileBitmap: Bitmap? = null

    init {
        val mark = ColorUtils.blendARGB(base, ink, 0.14f)
        val tile = when (pattern) {
            CoverStyle.DOTS -> tile((20 * density).toInt()) { c, s, p ->
                c.drawCircle(s / 2f, s / 2f, 2.2f * density, p)
            }
            CoverStyle.STRIPES -> tile((18 * density).toInt()) { c, s, p ->
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f * density
                val f = s.toFloat()
                // Lines x + y = 0, s, 2s: seamless when tiled.
                c.drawLine(-f, f, f, -f, p)
                c.drawLine(0f, f, f, 0f, p)
                c.drawLine(0f, 2 * f, 2 * f, 0f, p)
            }
            else -> tile((28 * density).toInt()) { c, s, p ->
                val w = maxOf(1f, density)
                c.drawRect(0f, 0f, w, s.toFloat(), p)
                c.drawRect(0f, 0f, s.toFloat(), w, p)
            }
        }
        tile?.let { paintMarks(it, mark) }
    }

    private fun tile(size: Int, draw: (Canvas, Int, Paint) -> Unit): Bitmap? {
        if (size <= 0) return null
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        draw(Canvas(bmp), size, p)
        return bmp
    }

    private fun paintMarks(alphaTile: Bitmap, mark: Int) {
        // Recolor the white marks to [mark] over the base, then repeat.
        val size = alphaTile.width
        val colored = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(colored)
        c.drawColor(base)
        val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = android.graphics.PorterDuffColorFilter(mark, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        c.drawBitmap(alphaTile, 0f, 0f, tint)
        alphaTile.recycle()
        tileBitmap = colored
        paint.shader = BitmapShader(colored, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun draw(canvas: Canvas) {
        if (tileBitmap == null) canvas.drawColor(base) else canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
