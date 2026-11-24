package app.lumen.player.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import app.lumen.player.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Small poster/backdrop loader: memory LRU + disk cache, newest requests first (so the row you're
 * looking at loads before the ones you scrolled past). No third-party dependency.
 */
object Images {
	private lateinit var cacheDir: File
	private val main = Handler(Looper.getMainLooper())

	private val memory = object : LruCache<String, Bitmap>(
		(Runtime.getRuntime().maxMemory() / 1024 / 6).toInt().coerceAtLeast(16 * 1024)
	) {
		override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
	}

	/** LIFO queue: the most recently requested image is fetched first. */
	private class LifoQueue : LinkedBlockingDeque<Runnable>() {
		override fun offer(element: Runnable): Boolean = offerFirst(element)
	}

	private val executor = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, LifoQueue())

	fun init(context: Context) {
		if (::cacheDir.isInitialized) return
		cacheDir = File(context.cacheDir, "images").apply { mkdirs() }
		executor.execute { trimDisk() }
	}

	/**
	 * Loads [url] into [view]. The view's previous image is cleared immediately so recycled
	 * cards never flash the wrong poster.
	 */
	fun load(view: ImageView, url: String?, fade: Boolean = true, onLoaded: ((Bitmap?) -> Unit)? = null) {
		view.setTag(R.id.tag_image_url, url)
		if (url.isNullOrBlank()) {
			view.setImageDrawable(null)
			onLoaded?.invoke(null)
			return
		}
		memory.get(url)?.let {
			view.setImageBitmap(it)
			view.alpha = 1f
			onLoaded?.invoke(it)
			return
		}
		view.setImageDrawable(null)
		executor.execute {
			val bitmap = runCatching { fromDisk(url) ?: download(url) }
				.onFailure { Log.d("Images", "Failed $url: ${it.message}") }
				.getOrNull()
			if (bitmap != null) memory.put(url, bitmap)
			main.post {
				if (view.getTag(R.id.tag_image_url) != url) return@post
				view.setImageBitmap(bitmap)
				if (fade && bitmap != null) {
					view.alpha = 0f
					view.animate().alpha(1f).setDuration(180L).start()
				}
				onLoaded?.invoke(bitmap)
			}
		}
	}

	/**
	 * Loads [url] without a view, optionally transforming it on the loader thread (e.g. [Blur.soft]),
	 * and hands the result to [onLoaded] on the main thread.
	 */
	fun fetch(url: String, transform: ((Bitmap) -> Bitmap)? = null, onLoaded: (Bitmap?) -> Unit) {
		executor.execute {
			val bitmap = runCatching {
				val raw = memory.get(url) ?: (fromDisk(url) ?: download(url))?.also { memory.put(url, it) }
				if (raw != null && transform != null) transform(raw) else raw
			}.onFailure { Log.d("Images", "Failed $url: ${it.message}") }.getOrNull()
			main.post { onLoaded(bitmap) }
		}
	}

	private fun fileFor(url: String): File {
		val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
		return File(cacheDir, digest.joinToString("") { "%02x".format(it) })
	}

	private fun fromDisk(url: String): Bitmap? {
		val file = fileFor(url)
		if (!file.exists()) return null
		file.setLastModified(System.currentTimeMillis())
		return BitmapFactory.decodeFile(file.absolutePath)
	}

	private fun download(url: String): Bitmap? {
		val connection = (URL(url).openConnection() as HttpURLConnection).apply {
			connectTimeout = 8_000
			readTimeout = 15_000
		}
		try {
			if (connection.responseCode !in 200..299) return null
			val bytes = connection.inputStream.use { it.readBytes() }
			val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
			runCatching { fileFor(url).writeBytes(bytes) }
			return bitmap
		} finally {
			connection.disconnect()
		}
	}

	/** Keeps the disk cache under ~200 MB by deleting the least recently used files. */
	private fun trimDisk() {
		val files = cacheDir.listFiles()?.toList() ?: return
		var total = files.sumOf { it.length() }
		val limit = 200L * 1024 * 1024
		if (total <= limit) return
		for (file in files.sortedBy { it.lastModified() }) {
			total -= file.length()
			file.delete()
			if (total <= limit * 3 / 4) break
		}
	}

	fun clearMemory() = memory.evictAll()
}
