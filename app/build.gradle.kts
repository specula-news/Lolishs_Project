plugins {
	id("com.android.application")
}

// GitHub Actions numbers every build; that number becomes the app version (3.0.<build>), so the
// in-app updater can tell which GitHub release is newer. Local builds are 3.0.0.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val appVersionName = "3.0.$buildNumber"

android {
	namespace = "app.lumen.player"
	compileSdk = 37

	defaultConfig {
		minSdk = 23
		targetSdk = 36
		versionCode = 1000 + buildNumber
		versionName = appVersionName

		// The Shield is arm64; most Fire TV sticks run a 32-bit userland.
		ndk {
			abiFilters += listOf("arm64-v8a", "armeabi-v7a")
			// Screenshot builds run on an x86 Android TV emulator (tools/screenshots).
			if (project.hasProperty("emulatorAbis")) abiFilters += listOf("x86", "x86_64")
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
			manifestPlaceholders["appLabel"] = "@string/app_name"
		}
		create("standalone") {
			dimension = "id"
			applicationId = "app.lumen.player"
			manifestPlaceholders["appLabel"] = "@string/app_name_standalone"
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

base.archivesName.set("lolish-project-v$appVersionName")

dependencies {
	// Kotlin / AndroidX
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
	implementation("androidx.core:core-ktx:1.19.1")
	implementation("androidx.activity:activity:1.13.0")
	implementation("androidx.lifecycle:lifecycle-runtime:2.11.0")
	implementation("androidx.recyclerview:recyclerview:1.4.0")

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
