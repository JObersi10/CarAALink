package dev.caraalink.video

import android.app.Presentation
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.io.File

/**
 * The screen the car shows when nothing is being mirrored: a big clock in
 * the middle and large tiles for a touchscreen in a moving car. Rendered on
 * our own private virtual display, so it needs no screen-capture permission
 * and Samsung DeX never sees it.
 */
class CarHomePresentation(
    context: Context,
    display: Display,
    private val tiles: List<Tile>,
) : Presentation(context, display) {

    data class Tile(val label: String, val glyph: String, val color: Int, val onTap: () -> Unit)

    private lateinit var statusView: TextView
    private lateinit var infoView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(24)
        val frame = FrameLayout(context).apply { setBackgroundColor(Color.parseColor("#0A0F12")) }
        backgroundFile(context).takeIf { it.exists() }?.let { file ->
            BitmapFactory.decodeFile(file.path)?.let { bmp ->
                frame.addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(BitmapDrawable(resources, bmp))
                    setColorFilter(Color.argb(90, 0, 0, 0)) // keep text readable on bright photos
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        statusView = TextView(context).apply {
            setTextColor(Color.parseColor("#C9D3DA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        root.addView(statusView)

        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        center.addView(TextClock(context).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 84f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        center.addView(TextClock(context).apply {
            format12Hour = "EEEE, MMMM d"
            format24Hour = "EEEE, d MMMM"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E3E8EC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })
        infoView = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E3E8EC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, dp(6), 0, 0)
        }
        center.addView(infoView)
        root.addView(center)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150))
        }
        tiles.forEachIndexed { i, tile ->
            row.addView(tileView(tile).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = dp(16)
                }
            })
        }
        root.addView(row)
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)
    }

    fun setStatus(text: String) {
        if (::statusView.isInitialized) statusView.text = text
    }

    /** Line under the date (weather, etc.). */
    fun setInfo(text: String) {
        if (::infoView.isInitialized) infoView.text = text
    }

    private fun tileView(tile: Tile) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(tile.color)
        }
        isClickable = true
        setOnClickListener { tile.onTap() }
        addView(TextView(context).apply {
            text = tile.glyph
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
        })
        addView(TextView(context).apply {
            text = tile.label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
    ).toInt()

    companion object {
        /** Image the owner picked on the phone (Settings tab in the app). */
        fun backgroundFile(context: Context) = File(context.applicationContext.filesDir, "car_background.jpg")
    }
}
