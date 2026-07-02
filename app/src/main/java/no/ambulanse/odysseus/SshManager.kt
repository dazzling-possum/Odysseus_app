package no.ambulanse.odysseus

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import kotlin.concurrent.thread

/**
 * SshManager
 * ---------------------------------------------------------------------
 * A thin wrapper around JSch that opens an interactive SSH shell (a PTY)
 * to a host on the local network and pumps bytes both ways:
 *
 *   * connect() runs on a background thread and, once the shell channel
 *     is open, streams everything the server prints to the listener.
 *   * send() writes the user's keystrokes back to the shell.
 *
 * All Listener callbacks fire on a background thread — the Activity must
 * hop to the UI thread before touching views.
 *
 * SECURITY NOTE: StrictHostKeyChecking is disabled so a first-time
 * connection to a LAN host "just works" for this prototype. That means
 * the host's identity is not verified (a man-in-the-middle on the LAN
 * could impersonate it). For production, pin/verify the host key.
 */
class SshManager(private val listener: Listener) {

    interface Listener {
        fun onConnected()
        fun onOutput(text: String)
        fun onError(message: String)
        fun onDisconnected()
    }

    data class Config(
        val host: String,
        val port: Int,
        val user: String,
        val password: String?,   // used when privateKey is null/blank
        val privateKey: String?, // OpenSSH/PEM private key text
        val passphrase: String?  // for an encrypted private key
    )

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var out: OutputStream? = null

    @Volatile private var running = false

    /** Open the connection. Returns immediately; work happens on a thread. */
    fun connect(cfg: Config) {
        thread(name = "ssh-connect") {
            try {
                val jsch = JSch()
                if (!cfg.privateKey.isNullOrBlank()) {
                    val pass = cfg.passphrase?.takeIf { it.isNotEmpty() }
                        ?.toByteArray(Charsets.UTF_8)
                    jsch.addIdentity(
                        "odysseus",
                        cfg.privateKey.toByteArray(Charsets.UTF_8),
                        null,
                        pass
                    )
                }
                val s = jsch.getSession(cfg.user, cfg.host, cfg.port)
                if (cfg.privateKey.isNullOrBlank() && !cfg.password.isNullOrEmpty()) {
                    s.setPassword(cfg.password)
                }
                // Prototype: accept unknown host keys automatically.
                val props = Properties()
                props["StrictHostKeyChecking"] = "no"
                s.setConfig(props)
                s.connect(CONNECT_TIMEOUT_MS)

                val ch = s.openChannel("shell") as ChannelShell
                ch.setPtyType("xterm-256color")
                ch.setPtySize(DEFAULT_COLS, DEFAULT_ROWS, 0, 0)
                val input = ch.inputStream
                out = ch.outputStream
                ch.connect(CHANNEL_TIMEOUT_MS)

                session = s
                channel = ch
                running = true
                listener.onConnected()
                readLoop(input)
            } catch (e: Exception) {
                listener.onError(e.message ?: "SSH connection failed")
                cleanup()
            }
        }
    }

    private fun readLoop(input: InputStream) {
        val buffer = ByteArray(READ_BUFFER)
        try {
            while (running) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) listener.onOutput(String(buffer, 0, n, Charsets.UTF_8))
            }
        } catch (e: Exception) {
            if (running) listener.onError(e.message ?: "Read error")
        } finally {
            cleanup()
            listener.onDisconnected()
        }
    }

    /** Send raw bytes (a keystroke or an escape sequence) to the shell. */
    fun send(bytes: ByteArray) {
        val stream = out ?: return
        try {
            stream.write(bytes)
            stream.flush()
        } catch (e: Exception) {
            // The connection likely dropped; the read loop will report it.
        }
    }

    fun send(text: String) = send(text.toByteArray(Charsets.UTF_8))

    /** Tell the server the terminal was resized. */
    fun resize(cols: Int, rows: Int) {
        try {
            channel?.setPtySize(cols, rows, 0, 0)
        } catch (e: Exception) {
            // non-fatal
        }
    }

    fun isConnected(): Boolean = running

    fun disconnect() {
        running = false
        cleanup()
    }

    private fun cleanup() {
        running = false
        try { channel?.disconnect() } catch (e: Exception) {}
        try { session?.disconnect() } catch (e: Exception) {}
        channel = null
        session = null
        out = null
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val CHANNEL_TIMEOUT_MS = 10000
        private const val READ_BUFFER = 8192
        private const val DEFAULT_COLS = 120
        private const val DEFAULT_ROWS = 40
    }
}
