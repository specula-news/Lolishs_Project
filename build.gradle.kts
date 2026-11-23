// Versions mirror the Jellyfin Android TV app (Nov 2026), which ships this exact
// Media3 + FFmpeg + libass combination on Shield/Fire TV devices.
plugins {
	id("com.android.application") version "9.5.1" apply false
}

buildscript {
	dependencies {
		// AGP 9 uses built-in Kotlin; this pins the Kotlin compiler version.
		classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
	}
}
