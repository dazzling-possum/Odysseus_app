package no.ambulanse.odysseus

/**
 * TerminalKeys
 * ---------------------------------------------------------------------
 * A web terminal (xterm.js / ttyd / wetty / gotty ...) reacts to real
 * `keydown` events. Android's soft keyboard cannot produce Ctrl, Alt,
 * Esc, Tab or the arrow keys, so a shell is unusable without help.
 *
 * This file defines an accessory key bar (Esc, Tab, Ctrl, arrows, …)
 * and the JavaScript that synthesises the matching KeyboardEvent inside
 * the page, so tapping a key behaves like pressing it on a hardware
 * keyboard.
 */

/** Sticky modifier keys that toggle rather than send a keystroke. */
enum class KeyModifier { NONE, CTRL, ALT }

/** One button in the accessory bar. */
data class TermKey(
    val label: String,
    val key: String = "",
    val keyCode: Int = 0,
    val code: String = "",
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val modifier: KeyModifier = KeyModifier.NONE
)

object TerminalKeys {

    /** The keys shown, left to right, in the scrollable accessory bar. */
    val BAR: List<TermKey> = listOf(
        TermKey("ESC", key = "Escape", keyCode = 27, code = "Escape"),
        TermKey("TAB", key = "Tab", keyCode = 9, code = "Tab"),
        TermKey("CTRL", modifier = KeyModifier.CTRL),
        TermKey("ALT", modifier = KeyModifier.ALT),
        TermKey("↑", key = "ArrowUp", keyCode = 38, code = "ArrowUp"),
        TermKey("↓", key = "ArrowDown", keyCode = 40, code = "ArrowDown"),
        TermKey("←", key = "ArrowLeft", keyCode = 37, code = "ArrowLeft"),
        TermKey("→", key = "ArrowRight", keyCode = 39, code = "ArrowRight"),
        TermKey("^C", key = "c", keyCode = 67, code = "KeyC", ctrl = true),
        TermKey("^D", key = "d", keyCode = 68, code = "KeyD", ctrl = true),
        TermKey("^Z", key = "z", keyCode = 90, code = "KeyZ", ctrl = true),
        TermKey("^L", key = "l", keyCode = 76, code = "KeyL", ctrl = true),
        TermKey("|", key = "|"),
        TermKey("~", key = "~"),
        TermKey("/", key = "/"),
        TermKey("\\", key = "\\"),
        TermKey("-", key = "-"),
        TermKey("HOME", key = "Home", keyCode = 36, code = "Home"),
        TermKey("END", key = "End", keyCode = 35, code = "End"),
        TermKey("PGUP", key = "PageUp", keyCode = 33, code = "PageUp"),
        TermKey("PGDN", key = "PageDown", keyCode = 34, code = "PageDown")
    )

    /**
     * JavaScript installed once per page. It exposes `window.__odyKey(spec)`
     * which focuses the terminal's input and dispatches a keydown/keyup
     * pair. keyCode/which are read-only on a constructed KeyboardEvent, so
     * we redefine them on the instance (many terminals still read them).
     */
    const val BOOTSTRAP_JS: String = """
        (function(){
          if (window.__odyKey) return;
          function target(){
            return document.querySelector('.xterm-helper-textarea')
                || document.querySelector('textarea')
                || document.activeElement
                || document.body;
          }
          window.__odyKey = function(s){
            try {
              var t = target();
              if (t && t.focus) t.focus();
              function fire(type){
                var e = new KeyboardEvent(type, {
                  key: s.key, code: s.code || '',
                  ctrlKey: !!s.ctrl, altKey: !!s.alt, shiftKey: !!s.shift,
                  bubbles: true, cancelable: true
                });
                try {
                  Object.defineProperty(e, 'keyCode', { get: function(){ return s.keyCode||0; } });
                  Object.defineProperty(e, 'which',   { get: function(){ return s.keyCode||0; } });
                } catch(_) {}
                t.dispatchEvent(e);
              }
              fire('keydown');
              fire('keyup');
            } catch(err) { if (window.console) console.warn('odyKey', err); }
          };
        })();
    """

    /** Build the JS call for a key press, merging the sticky modifiers. */
    fun jsCall(k: TermKey, ctrlActive: Boolean, altActive: Boolean): String {
        val ctrl = k.ctrl || ctrlActive
        val alt = k.alt || altActive
        val spec = buildString {
            append("{")
            append("key:'").append(esc(k.key)).append("',")
            append("code:'").append(esc(k.code)).append("',")
            append("keyCode:").append(k.keyCode).append(",")
            append("ctrl:").append(ctrl).append(",")
            append("alt:").append(alt).append(",")
            append("shift:").append(k.shift)
            append("}")
        }
        return "if(window.__odyKey)window.__odyKey($spec);"
    }

    /** Escape a character for embedding inside a single-quoted JS string. */
    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'")
}
