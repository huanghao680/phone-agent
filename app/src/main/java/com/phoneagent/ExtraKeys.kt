package com.phoneagent

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalEmulator

/**
 * Termux-style extra keys row: writes the matching control sequences straight
 * into the PTY. Held modifiers (CTRL/ALT/FN) latch until tapped again and
 * apply to the next key press, mirroring Termux's behavior.
 */
object ExtraKeys {

    private class Key(
        val label: String,
        val bytes: (() -> ByteArray)? = null,
        val modifier: String? = null,
        val weight: Float = 1f,
    )

    // standard control sequences
    private val ESC = byteArrayOf(0x1b)
    private val TAB = byteArrayOf(0x09)
    private val ENTER = byteArrayOf(0x0d)
    private val UP = byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte())
    private val DOWN = byteArrayOf(0x1b, '['.code.toByte(), 'B'.code.toByte())
    private val RIGHT = byteArrayOf(0x1b, '['.code.toByte(), 'C'.code.toByte())
    private val LEFT = byteArrayOf(0x1b, '['.code.toByte(), 'D'.code.toByte())
    private val HOME = byteArrayOf(0x1b, '['.code.toByte(), 'H'.code.toByte())
    private val END = byteArrayOf(0x1b, '['.code.toByte(), 'F'.code.toByte())
    private val PGUP = byteArrayOf(0x1b, '['.code.toByte(), '5'.code.toByte(), '~'.code.toByte())
    private val PGDN = byteArrayOf(0x1b, '['.code.toByte(), '6'.code.toByte(), '~'.code.toByte())

    private fun ctrl(c: Char): ByteArray = byteArrayOf((c.code and 0x1f).toByte())

    private fun row(latched: Map<String, Boolean>) = listOf(
        Key("ESC", { ESC }),
        Key("CTRL", modifier = "ctrl"),
        Key("ALT", modifier = "alt"),
        Key("TAB", { TAB }),
        Key("◀", { LEFT }, weight = 0.7f),
        Key("▼", { DOWN }, weight = 0.7f),
        Key("▲", { UP }, weight = 0.7f),
        Key("▶", { RIGHT }, weight = 0.7f),
        Key("HOME", { HOME }),
        Key("END", { END }),
        Key("PGUP", { PGUP }),
        Key("PGDN", { PGDN }),
        Key("ENTER", { ENTER }, weight = 1.2f),
        Key("-", { "-".toByteArray() }),
        Key("/", { "/".toByteArray() }),
        Key("|", { "|".toByteArray() }),
        Key("~", { "~".toByteArray() }),
    )

    /**
     * Populates [row] with key buttons bound to [sessionProvider]. Modifier
     * buttons latch visually (accent color) and prepend their flag to the
     * next character/arrow key.
     */
    fun attach(
        row: LinearLayout,
        scroll: HorizontalScrollView,
        sessionProvider: () -> TerminalSession?,
    ) {
        val ctx = row.context
        val latched = mutableMapOf("ctrl" to false, "alt" to false, "fn" to false)
        val accent = 0xFF22D3EE.toInt()
        val normal = 0xFF161B22.toInt()
        val pressed = 0xFF2A3441.toInt()

        fun write(data: ByteArray) {
            var bytes = data
            if (latched["ctrl"] == true && bytes.size == 1 && bytes[0] in 0x61..0x7a) {
                bytes = byteArrayOf((bytes[0].toInt() and 0x1f).toByte())
            }
            if (latched["alt"] == true) bytes = ESC + bytes
            sessionProvider()?.write(bytes, 0, bytes.size)
            latched["ctrl"] = false
            latched["alt"] = false
        }

        fun makeButton(key: Key): View {
            val tv = TextView(ctx)
            tv.text = key.label
            tv.typeface = Typeface.MONOSPACE
            tv.gravity = Gravity.CENTER
            tv.setSingleLine()
            val pad = (10 * ctx.resources.displayMetrics.density).toInt()
            tv.setPadding(pad, 6, pad, 6)
            val lp = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                key.weight,
            )
            tv.layoutParams = lp
            if (key.modifier != null) {
                tv.setOnClickListener {
                    val on = !(latched[key.modifier] ?: false)
                    latched[key.modifier] = on
                    tv.setBackgroundColor(if (on) accent else normal)
                    tv.setTextColor(if (on) 0xFF0B1220.toInt() else 0xFFE2E8F0.toInt())
                }
                tv.setBackgroundColor(normal)
                tv.setTextColor(0xFFE2E8F0.toInt())
            } else {
                tv.setOnClickListener {
                    tv.setBackgroundColor(pressed)
                    tv.postDelayed({ tv.setBackgroundColor(normal) }, 90)
                    write(key.bytes!!.invoke())
                }
                tv.setBackgroundColor(normal)
                tv.setTextColor(0xFFE2E8F0.toInt())
            }
            return tv
        }

        // two stacked half-rows inside the scroll for compactness
        val keys = row(latched)
        row.removeAllViews()
        row.orientation = LinearLayout.VERTICAL
        val half = (keys.size + 1) / 2
        for (chunk in keys.chunked(half)) {
            val l = LinearLayout(ctx)
            l.orientation = LinearLayout.HORIZONTAL
            for (k in chunk) l.addView(makeButton(k))
            row.addView(l)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_LEFT) }
    }
}
