package app.lumen.player.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.util.LruCache
import app.lumen.player.jellyfin.JellyfinApi
import app.lumen.player.jellyfin.Session
import app.lumen.player.jellyfin.TrickplayInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Seek-preview thumbnails from Jellyfin's trickplay tiles. Tiles are cached as compressed JPEG bytes
 * and only the needed thumbnail is decoded, so memory use stays tiny even for 4K libraries.
 */
class TrickplayLoader(
	private val api: JellyfinApi,
	private val session: Session,
	private val itemId: String,
	private val mediaSourceId: String?,
	private val info: TrickplayInfo,
	private val scope: CoroutineScope,
) {
	private val tiles = LruCache<Int, ByteArray>(8)
	private var job: Job? = null
	private var lastThumbIndex = -1

	val aspectRatio: Float get() = info.width.toFloat() / info.height

	fun load(positionMs: Long, onReady: (Bitmap?) -> Unit) {
		val thumbIndex = (positionMs / info.intervalMs).toInt().coerceIn(0, info.thumbnailCount - 1)
		if (thumbIndex == lastThumbIndex) return
		lastThumbIndex = thumbIndex
		val perTile = info.tileWidth * info.tileHeight
		val tileIndex = thumbIndex / perTile
		val local = thumbIndex % perTile
		val x = (local % info.tileWidth) * info.width
		val y = (local / info.tileWidth) * info.height

		job?.cancel()
		job = scope.launch {
			val bitmap = runCatching {
				val bytes = tiles.get(tileIndex) ?: api.bytes(
					api.trickplayTileUrl(session, itemId, mediaSourceId, info.width, tileIndex),
					session.token,
				).also { tiles.put(tileIndex, it) }
				withContext(Dispatchers.Default) { decode(bytes, Rect(x, y, x + info.width, y + info.height)) }
			}.onFailure { Log.w("Trickplay", "Thumbnail failed", it) }.getOrNull()
			if (isActive) onReady(bitmap)
		}
	}

	fun reset() {
		lastThumbIndex = -1
	}

	private fun decode(bytes: ByteArray, region: Rect): Bitmap? {
		val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
		} else {
			@Suppress("DEPRECATION")
			BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
		} ?: return null
		return try {
			val safe = Rect(region)
			if (!safe.intersect(0, 0, decoder.width, decoder.height)) return null
			decoder.decodeRegion(safe, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
		} finally {
			decoder.recycle()
		}
	}
}
