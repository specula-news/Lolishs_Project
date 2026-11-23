plugins {
	id("com.android.application")
}

android {
	namespace = "app.lumen.player"
	compileSdk = 37

	defaultConfig {
		minSdk = 23
		targetSdk = 36
		versionCode = 1
		versionName = "1.0.0"

		// The Shield is arm64; most Fire TV sticks run a 32-bit userland.
		ndk {
			abiFilters += listOf("arm64-v8a", "armeabi-v7a")
		}
	}

	// Two builds of the same app:
	//  - "jellyfin": uses mpv-android's package id, so the Jellyfin apps treat it like mpv
	//    (they pass external subtitles and read back the resume position / completion).
	//    Cannot be installed next to the real mpv-android app.
	//  - "standalone": its own package id; works as a generic external player.
	flavorDimensions += "id"
	productFlavors {
		create("jellyfin") {
			dimension = "id"
			applicationId = "is.xyz.mpv"
			manifestPlaceholders["appLabel"] = "Lumen Player"
		}
		create("standalone") {
			dimension = "id"
			applicationId = "app.lumen.player"
			manifestPlaceholders["appLabel"] = "Lumen Player (standalone)"
		}
	}

	signingConfigs {
		// A fixed key so every build (local or GitHub Actions) can update the installed app.
		// Replace it with your own keystore if you publish the app anywhere.
		create("lumen") {
			storeFile = file("lumen-release.jks")
			storePassword = "lumenplayer"
			keyAlias = "lumen"
			keyPassword = "lumenplayer"
		}
	}

	buildTypes {
		release {
			// Media3, libVLC and libass rely on JNI/reflection; skipping R8 keeps things safe.
			isMinifyEnabled = false
			isShrinkResources = false
			signingConfig = signingConfigs.getByName("lumen")
		}
		debug {
			signingConfig = signingConfigs.getByName("lumen")
		}
	}

	buildFeatures {
		buildConfig = true
	}

	packaging {
		jniLibs {
			// Required so libVLC / FFmpeg native libraries load from the APK on all devices.
			useLegacyPackaging = true
			// libVLC, FFmpeg and libass may each bundle the C++ runtime.
			pickFirsts += listOf("lib/**/libc++_shared.so")
		}
	}

	lint {
		abortOnError = false
		checkReleaseBuilds = false
	}
}

base.archivesName.set("lumen-player-v1.0.0")

dependencies {
	// Kotlin / AndroidX
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
	implementation("androidx.core:core-ktx:1.19.1")
	implementation("androidx.activity:activity:1.13.0")
	implementation("androidx.lifecycle:lifecycle-runtime:2.11.0")

	// Primary engine: Media3 ExoPlayer (hardware decode, HDR/Dolby Vision, passthrough)
	implementation("androidx.media3:media3-exoplayer:1.11.1")
	implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
	implementation("androidx.media3:media3-ui:1.11.1")
	// FFmpeg audio decoders (DTS, TrueHD, AC3/E-AC3, FLAC, ALAC...) used when the AVR/TV can't take the bitstream
	implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
	// libass rendering for styled ASS/SSA subtitles
	implementation("io.github.peerless2012:ass-media:0.5.1")

	// Fallback engine: libVLC (AVI/WMV/RMVB/odd codecs)
	implementation("org.videolan.android:libvlc-all:3.7.6")
}
