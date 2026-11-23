package app.lumen.player.engine

/**
 * The "players" you can switch between: which engine decodes, and whether the server sends the
 * original file (Direct) or transcodes it to a lighter H.264 stream at a fixed bitrate.
 */
enum class PlayerMode(val engine: Engine, val bitrate: Int, val label: String, val hint: String) {
	EXO_DIRECT(Engine.EXO, 0, "ExoPlayer · Direct", "Original quality, HDR / Dolby Vision, audio passthrough"),
	VLC_DIRECT(Engine.VLC, 0, "VLC · Direct", "Original file, plays odd formats (AVI, WMV…)"),
	EXO_20(Engine.EXO, 20_000_000, "ExoPlayer · 20 Mbps", "Server transcodes to H.264 — fixes most stutter"),
	EXO_10(Engine.EXO, 10_000_000, "ExoPlayer · 10 Mbps", "Lighter stream for slow connections"),
	EXO_4(Engine.EXO, 4_000_000, "ExoPlayer · 4 Mbps", "Lowest bandwidth"),
	VLC_10(Engine.VLC, 10_000_000, "VLC · 10 Mbps", "VLC with a server-transcoded stream");

	enum class Engine { EXO, VLC }

	val isTranscode: Boolean get() = bitrate > 0

	companion object {
		fun fromName(name: String?): PlayerMode? = entries.firstOrNull { it.name == name }

		/**
		 * What to try next when playback stutters. Buffering (network) favours lower bitrates;
		 * dropped frames (decoding) favour the other engine first.
		 */
		fun suggestions(current: PlayerMode, networkProblem: Boolean, tried: Set<PlayerMode>, canTranscode: Boolean): List<PlayerMode> {
			val order = if (networkProblem) {
				listOf(EXO_20, EXO_10, EXO_4, VLC_10, if (current.engine == Engine.EXO) VLC_DIRECT else EXO_DIRECT)
			} else {
				listOf(if (current.engine == Engine.EXO) VLC_DIRECT else EXO_DIRECT, EXO_20, VLC_10, EXO_10, EXO_4)
			}
			return order.filter { it != current && it !in tried && (canTranscode || !it.isTranscode) }
				.filter { !networkProblem || !it.isTranscode || current.bitrate == 0 || it.bitrate < current.bitrate || it.engine != current.engine }
				.distinct()
				.take(3)
		}
	}
}
