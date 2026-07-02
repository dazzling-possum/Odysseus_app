package no.ambulanse.odysseus

/**
 * AnsiTerminal
 * ---------------------------------------------------------------------
 * A deliberately small terminal emulator. A real shell over a PTY sends
 * printable text mixed with control characters and ANSI escape codes.
 * This class keeps a list of text lines and a cursor column, and applies
 * just enough of the protocol to make normal command output — including
 * progress bars that rewrite the line with '\r' — render correctly:
 *
 *   * printable chars, overwriting at the cursor,
 *   * carriage return / line feed / backspace / tab,
 *   * erase-to-end-of-line (CSI K) and clear-screen (CSI J),
 *   * colour and cursor-move codes are recognised and skipped.
 *
 * It is not a full VT100 (no 2-D cursor addressing), so full-screen apps
 * like vim or top won't render perfectly — but shells, build output and
 * agent logs read cleanly.
 */
class AnsiTerminal(private val maxLines: Int = 5000) {

    private val lines = ArrayList<StringBuilder>().apply { add(StringBuilder()) }
    private var col = 0

    // Parser state: 0 = normal, 1 = after ESC, 2 = inside CSI, 3 = inside OSC.
    private var state = 0
    private val csi = StringBuilder()

    @Synchronized
    fun feed(text: String) {
        for (c in text) {
            when (state) {
                1 -> esc(c)
                2 -> csiChar(c)
                3 -> oscChar(c)
                else -> normal(c)
            }
        }
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        lines.add(StringBuilder())
        col = 0
        state = 0
        csi.setLength(0)
    }

    private fun current() = lines[lines.size - 1]

    private fun normal(c: Char) {
        when (c) {
            ESC -> state = 1                     // start of escape sequence
            '\r' -> col = 0                      // carriage return
            '\n' -> newline()                   // line feed
            '\b' -> if (col > 0) col--          // backspace
            BEL -> {}                           // bell — ignore
            '\t' -> {                           // tab to next multiple of 8
                val next = ((col / 8) + 1) * 8
                while (col < next) put(' ')
            }
            else -> if (c >= ' ') put(c)        // printable character
        }
    }

    private fun put(c: Char) {
        val line = current()
        if (col < line.length) {
            line.setCharAt(col, c)
        } else {
            while (line.length < col) line.append(' ')
            line.append(c)
        }
        col++
    }

    private fun newline() {
        lines.add(StringBuilder())
        col = 0
        while (lines.size > maxLines) lines.removeAt(0)
    }

    private fun esc(c: Char) {
        when (c) {
            '[' -> { state = 2; csi.setLength(0) }  // CSI
            ']' -> state = 3                        // OSC (window title etc.)
            else -> state = 0                       // other ESC x — ignore
        }
    }

    private fun csiChar(c: Char) {
        // A CSI sequence ends on a byte in the range @ (0x40) to ~ (0x7E).
        if (c in '@'..'~') {
            handleCsi(c, csi.toString())
            state = 0
        } else {
            csi.append(c)
        }
    }

    private fun handleCsi(final: Char, params: String) {
        when (final) {
            'K' -> {                               // erase in line
                val line = current()
                when (params.toIntOrNull() ?: 0) {
                    0 -> if (col < line.length) line.setLength(col)  // to end
                    2 -> line.setLength(0)                            // whole line
                }
            }
            'J' -> {                               // erase in display
                if (params == "2" || params == "3") {
                    lines.clear()
                    lines.add(StringBuilder())
                    col = 0
                }
            }
            // 'm' (colours), 'A'/'B'/'C'/'D' (cursor moves), 'H'/'f'
            // (positioning) and the rest are intentionally skipped.
        }
    }

    private fun oscChar(c: Char) {
        // OSC ends at BEL, or at the ESC of an ESC-\ terminator.
        when (c) {
            BEL -> state = 0
            ESC -> state = 1
        }
    }

    companion object {
        private const val ESC = '\u001B'
        private const val BEL = '\u0007'
    }
}
