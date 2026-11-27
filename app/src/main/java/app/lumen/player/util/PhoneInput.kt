package app.lumen.player.util

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Type on the phone instead of with the remote: the TV serves one small page on the home network,
 * its address goes in a QR code, and what you send from the phone arrives in [onText].
 * The page only answers on a random secret path and only while it is open ([stop] closes it).
 */
class PhoneInput(
	private val title: String,
	private val hint: String,
	private val onText: (String) -> Unit,
) {
	private val main = Handler(Looper.getMainLooper())
	private val secret = randomSecret()
	private var server: ServerSocket? = null
	@Volatile private var closed = false

	/** Starts the page; returns its address (for the QR code), or null without a home network. */
	fun start(): String? {
		val ip = localAddress() ?: return null
		val socket = runCatching { ServerSocket(0) }.getOrNull() ?: return null
		server = socket
		Thread({
			while (!closed) {
				val client = runCatching { socket.accept() }.getOrNull() ?: break
				runCatching { handle(client) }
				runCatching { client.close() }
			}
		}, "PhoneInput").apply { isDaemon = true }.start()
		return "http://$ip:${socket.localPort}/$secret"
	}

	fun stop() {
		closed = true
		runCatching { server?.close() }
		server = null
	}

	private fun handle(client: Socket) {
		client.soTimeout = 15_000
		val input = BufferedInputStream(client.getInputStream())
		val head = readHead(input) ?: return
		val lines = head.split("\r\n")
		val (method, path) = lines.first().split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
		val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
			?.substringAfter(':')?.trim()?.toIntOrNull()?.coerceIn(0, 4096) ?: 0
		val out = client.getOutputStream()
		if (path.substringBefore('?').trimEnd('/') != "/$secret") {
			out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
			return
		}
		val page = if (method == "POST") {
			val body = ByteArray(length)
			var read = 0
			while (read < length) {
				val n = input.read(body, read, length - read)
				if (n < 0) break
				read += n
			}
			val text = String(body, 0, read, Charsets.UTF_8).split('&')
				.firstOrNull { it.startsWith("text=") }?.substringAfter('=')
				?.let { URLDecoder.decode(it, "UTF-8") }?.trim().orEmpty()
			if (text.isNotEmpty()) {
				main.post { if (!closed) onText(text) }
				page("Sent to the TV", "<p class=ok>✓ Sent. Look at your TV.</p>")
			} else {
				page(title, form("Type something first."))
			}
		} else {
			page(title, form(null))
		}
		val bytes = page.toByteArray(Charsets.UTF_8)
		out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
		out.write(bytes)
		out.flush()
	}

	private fun readHead(input: BufferedInputStream): String? {
		val buffer = ByteArrayOutputStream()
		var last4 = 0
		while (buffer.size() < 8192) {
			val b = input.read()
			if (b < 0) return null
			buffer.write(b)
			last4 = (last4 shl 8) or b
			if (last4 == 0x0D0A0D0A) return buffer.toString("UTF-8")
		}
		return null
	}

	private fun form(error: String?): String = """
		<form method=post>
		<input name=text autofocus autocomplete=off autocapitalize=off spellcheck=false inputmode=url placeholder="${escape(hint)}">
		${if (error != null) "<p class=err>${escape(error)}</p>" else ""}
		<button>Send to TV</button>
		</form>
	""".trimIndent()

	private fun page(heading: String, body: String): String = """
		<!doctype html><html><head><meta charset=utf-8>
		<meta name=viewport content="width=device-width,initial-scale=1">
		<title>${escape(heading)}</title>
		<style>
		body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0d0d0f;color:#f3efe6;font:17px system-ui,sans-serif}
		main{width:min(92vw,420px);padding:28px 24px;border-radius:22px;background:#17161a;box-shadow:0 0 0 1px #d4b05e55,0 20px 60px #000a}
		h1{margin:0 0 6px;font-size:22px} small{color:#a59f93}
		input{box-sizing:border-box;width:100%;margin:18px 0 12px;padding:15px 16px;font-size:18px;border-radius:14px;border:1px solid #3a3630;background:#0d0d0f;color:#fff}
		input:focus{outline:2px solid #d4b05e}
		button{width:100%;padding:15px;font-size:18px;font-weight:700;border:0;border-radius:14px;background:linear-gradient(90deg,#a6803a,#d4b05e,#f4dc99);color:#111}
		.ok{font-size:20px;color:#9fd88a}.err{color:#ff8a7a;margin:0 0 10px}
		</style></head><body><main><h1>${escape(heading)}</h1><small>Lolish's Project · Android TV</small>$body</main></body></html>
	""".trimIndent()

	private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

	companion object {
		/** The TV's address on the home network (Wi-Fi or cable). */
		fun localAddress(): String? = runCatching {
			NetworkInterface.getNetworkInterfaces().toList()
				.filter { it.isUp && !it.isLoopback && !it.isVirtual }
				.sortedBy { if (it.name.startsWith("eth") || it.name.startsWith("wlan")) 0 else 1 }
				.flatMap { it.inetAddresses.toList() }
				.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
				?.hostAddress
		}.getOrNull()

		/** A black-on-white QR code of [text], [size] pixels wide. */
		fun qr(text: String, size: Int): Bitmap {
			val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
			val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
			return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
		}

		private fun randomSecret(): String {
			val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
			val random = SecureRandom()
			return (1..10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
		}
	}
}
