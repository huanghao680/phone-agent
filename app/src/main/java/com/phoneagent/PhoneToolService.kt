package com.phoneagent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder

/**
 * Loopback control endpoint that exposes privileged device operations to agent
 * sessions: a bash tool runs `curl "http://127.0.0.1:8899/ui-dump"` and gets JSON
 * back. Binds to localhost only, no auth token (loopback + app sandbox).
 *
 * Anything needing adb-level rights goes through our Shizuku shell channel; when
 * Shizuku is unavailable the endpoint still answers with an explicit
 * "unavailable" payload so the agent can degrade instead of hanging.
 *
 * Credit: tool-surface idea borrowed from dsh-mobile-apk (registers tools as
 * plugins). We keep it transport-simple so any agent can call it from plain bash.
 */
class PhoneToolService : Service() {

    companion object {
        const val PORT = 8899
        private const val TAG = "phone-tool"
        private const val JSON_CT = "application/json; charset=utf-8"
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var alive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannel(this)
        startForeground(Notifications.ID_TOOLS, Notifications.build(this, "设备工具通道运行中"))
        if (!alive) {
            alive = true
            Thread({ serve() }, "phone-tool").start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun serve() {
        val ss = try {
            ServerSocket(PORT)
        } catch (e: Exception) {
            Log.w(TAG, "cannot bind $PORT", e)
            return
        }
        ss.reuseAddress = true
        server = ss
        Log.i(TAG, "listening on 127.0.0.1:$PORT")
        while (alive) {
            val s = try {
                ss.accept()
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                continue
            }
            Thread({
                try {
                    s.soTimeout = 20_000
                    respond(s)
                } catch (_: Exception) {
                } finally {
                    try {
                        s.close()
                    } catch (_: Exception) {
                    }
                }
            }).start()
        }
    }

    private fun respond(s: Socket) {
        val reader = s.getInputStream().bufferedReader()
        val req = reader.readLine() ?: return
        val parts = req.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val raw = parts[1]
        val qi = raw.indexOf('?')
        val path = if (qi >= 0) raw.substring(0, qi) else raw
        val query = parseQuery(if (qi >= 0) raw.substring(qi + 1) else "")
        // drain headers
        while (true) {
            val h = reader.readLine() ?: break
            if (h.isEmpty()) break
        }
        val (ct, body) = dispatch(method, path, query)
        s.getOutputStream().write(
            ("HTTP/1.1 200 OK\r\nContent-Type: $ct\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        s.getOutputStream().write(body)
        s.getOutputStream().flush()
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in q.split("&")) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            out[URLDecoder.decode(pair.substring(0, i), "UTF-8")] =
                URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }
        return out
    }

    /** Returns content type + raw bytes: /screenshot is a png, everything else JSON. */
    private fun dispatch(method: String, path: String, q: Map<String, String>): Pair<String, ByteArray> {
        if (path == "/") return Pair(JSON_CT, lastRoutes().toByteArray())
        // the png is not JSON, so it bypasses the shell wrapper entirely
        if (path == "/screenshot") return Pair("image/png", screenshotBytes())
        val body = withShell { granted, sh ->
            try {
                when (path) {
                    "/status" -> json(mapOf("shizuku" to granted))
                    "/ports" -> json(mapOf("ports" to ShizukuHelper.listeningPorts()))
                    "/device" -> json(
                        mapOf(
                            "model" to android.os.Build.MODEL,
                            "device" to android.os.Build.DEVICE,
                            "sdk" to android.os.Build.VERSION.SDK_INT,
                            "abi" to (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"),
                        ),
                    )
                    "/ui-dump" -> {
                        if (!granted) unavailable()
                        else {
                            val xml = sh("uiautomator dump /sdcard/pa-ui.xml >/dev/null 2>&1; cat /sdcard/pa-ui.xml")
                            if (xml.isBlank()) json(mapOf("error" to "uiautomator dump empty"))
                            else json(mapOf("xml" to xml.take(200_000)))
                        }
                    }
                    "/tap" -> {
                        if (!granted) unavailable()
                        else {
                            val x = q["x"]?.toIntOrNull()
                            val y = q["y"]?.toIntOrNull()
                            if (x == null || y == null) argErrJson("x,y")
                            else if (!inBounds(x, y)) {
                                json(mapOf("error" to "坐标越界 ($x,$y)，须在 0..${maxX()}x${maxY()} 内"))
                            } else {
                                sh("input tap $x $y")
                                json(mapOf("tapped" to listOf(x, y)))
                            }
                        }
                    }
                    "/input" -> {
                        if (!granted) unavailable()
                        else {
                            val text = q["text"]
                            if (text == null) argErrJson("text")
                            else json(inputText(sh, text))
                        }
                    }
                    "/key" -> {
                        if (!granted) unavailable()
                        else {
                            val k = q["code"] ?: "KEYCODE_BACK"
                            sh("input keyevent $k")
                            json(mapOf("key" to k))
                        }
                    }
                    "/clipboard" -> {
                        val text = q["text"]
                        if (text == null) {
                            json(mapOf("clipboard" to clipboardText()))
                        } else {
                            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("pa", text))
                            json(mapOf("set" to text.length))
                        }
                    }
                    "/vdisplay-create" -> {
                        val (id, msg) = VirtualDisplay.create()
                        json(mapOf("displayId" to id, "message" to msg))
                    }
                    "/vdisplay-destroy" -> {
                        val id = q["id"]?.toIntOrNull()
                        if (id == null) argErrJson("id")
                        else json(mapOf("message" to VirtualDisplay.destroy(id)))
                    }
                    "/vdisplay-launch" -> {
                        val comp = q["component"]
                        val id = q["id"]?.toIntOrNull()
                        if (comp == null || id == null) argErrJson("component,id")
                        else json(mapOf("message" to VirtualDisplay.launch(comp, id)))
                    }
                    "/vdisplay-tap" -> {
                        // absolute pixels only: normalized coords are refused on
                        // purpose (ambiguous denominator -> taps the real screen)
                        val x = q["x"]?.toIntOrNull()
                        val y = q["y"]?.toIntOrNull()
                        val id = q["id"]?.toIntOrNull()
                        if (x == null || y == null || id == null) argErrJson("x,y,id")
                        else if (!inBounds(x, y)) {
                            json(mapOf("error" to "坐标越界 ($x,$y)，须在 0..${maxX()}x${maxY()} 内"))
                        } else json(mapOf("message" to VirtualDisplay.tap(x, y, id)))
                    }
                    "/vdisplay-list" -> json(
                        mapOf(
                            "displays" to VirtualDisplay.list(),
                            "available" to VirtualDisplay.available(),
                            "supported" to VirtualDisplay.supported(),
                        ),
                    )
                    "/shell" -> {
                        val cmd = q["cmd"]
                        if (cmd == null) argErrJson("cmd")
                        else json(mapOf("out" to sh(cmd).take(50_000)))
                    }
                    else -> json(mapOf("error" to "unknown endpoint $path"))
                }
            } catch (e: Exception) {
                json(mapOf("error" to (e.message ?: "error")))
            }
        }
        return Pair(JSON_CT, body.toByteArray())
    }

    private fun lastRoutes(): String = json(
        mapOf(
            "service" to "phone-agent-tools",
            "endpoints" to listOf(
                "/status", "/ports", "/ui-dump", "/screenshot", "/tap", "/input",
                "/key", "/shell", "/clipboard", "/device",
                "/vdisplay-create", "/vdisplay-destroy", "/vdisplay-launch", "/vdisplay-tap", "/vdisplay-list",
            ),
            "shizuku" to ShizukuHelper.granted(),
        ),
    )

    /** Runs [block] with a shell runner; privileged when Shizuku is granted. */
    private fun withShell(block: (Boolean, (String) -> String) -> String): String =
        try {
            val granted = ShizukuHelper.granted()
            val run: (String) -> String = if (granted) {
                { cmd -> runCollect(ShizukuHelper.sh(cmd)) }
            } else {
                { cmd ->
                    val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
                    pb.redirectErrorStream(true)
                    runCollect(pb.start())
                }
            }
            block(granted, run)
        } catch (e: Exception) {
            json(mapOf("error" to ("shell error: " + (e.message ?: ""))))
        }

    private fun runCollect(p: Process): String {
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val err = try {
            p.errorStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
        try {
            p.waitFor()
        } catch (_: Exception) {
        }
        return (out + err).trim()
    }

    private fun screenshotBytes(): ByteArray {
        if (!ShizukuHelper.granted()) {
            return json(unavailable().let { mapOf("error" to "shizuku unavailable") }).toByteArray()
        }
        return try {
            val p = ShizukuHelper.sh("screencap -p 2>/dev/null")
            val bytes = p.inputStream.use { it.readBytes() }
            try {
                p.waitFor()
            } catch (_: Exception) {
            }
            bytes
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    /**
     * Types text. Android's `input text` silently fails on non-ASCII (the command
     * throws for CJK), so ASCII goes through it with an exit-code check and
     * everything else falls back to clipboard + KEYCODE_PASTE — which needs the
     * target field focused and only works where paste is allowed. The response
     * says which path ran; callers must not assume success.
     */
    private fun inputText(sh: (String) -> String, text: String): Map<String, Any?> {
        val ascii = text.all { it.code in 32..126 }
        return if (ascii) {
            // POSIX single-quote escaping; the payload stays inline because the
            // shell uid cannot read files inside our private cacheDir
            val esc = text.replace("'", "'\\''")
            val rc = runExit(sh, "input text '" + esc + "'")
            if (rc == 0) mapOf("typed" to text.length, "via" to "input-text")
            else mapOf("error" to "input text failed (rc=$rc)")
        } else {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("pa", text))
            // Verified on device: KEYCODE_PASTE pastes into Compose/Miuix fields,
            // while an injected Ctrl+V chord reports rc=0 but is ignored by them.
            val rc = runExit(sh, "input keyevent 279") // KEYCODE_PASTE
            if (rc == 0) mapOf("pasted" to text.length, "via" to "clipboard-paste")
            else mapOf("error" to "paste failed (rc=$rc); focus a text field first")
        }
    }

    /**
     * Runs cmd and returns its real exit status. The trailing `echo rc=$?` rides
     * the command's own stdout — no temp files, which the shell uid could neither
     * write into nor read from our private cacheDir.
     */
    private fun runExit(sh: (String) -> String, cmd: String): Int {
        val out = sh(cmd + " ; echo pa-rc=\$?")
        val m = Regex("pa-rc=(\\d+)\\s*\\z").find(out.trim())
        return m?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    /** Screen size in absolute pixels; taps outside this are refused. */
    private fun maxX(): Int = android.content.res.Resources.getSystem().displayMetrics.widthPixels

    private fun maxY(): Int = android.content.res.Resources.getSystem().displayMetrics.heightPixels

    private fun inBounds(x: Int, y: Int): Boolean = x in 0..maxX() && y in 0..maxY()

    private fun clipboardText(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
    }

    private fun unavailable(): String = json(mapOf("error" to "shizuku unavailable"))

    private fun argErrJson(name: String): String = json(mapOf("error" to "$name required"))

    private fun json(value: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in value) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escape(k)).append("\":").append(serialize(v))
        }
        return sb.append('}').toString()
    }

    private fun serialize(v: Any?): String = when (v) {
        null -> "null"
        is Number, is Boolean -> v.toString()
        is List<*> -> v.joinToString(",", "[", "]") { serialize(it) }
        is Array<*> -> v.joinToString(",", "[", "]") { serialize(it) }
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") {
            "\"" + escape(it.key.toString()) + "\":" + serialize(it.value)
        }
        else -> "\"" + escape(v.toString()) + "\""
    }

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
