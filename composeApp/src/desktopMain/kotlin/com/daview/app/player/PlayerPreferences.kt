package com.daview.app.player

import com.daview.app.platform.createSettingsStore

/**
 * Desktop player settings, in the same `java.util.prefs` node the rest of the
 * client uses. They are per machine on purpose: which GPU is in the box, and
 * whether libmpv is installed, is not something the sync file should carry to
 * another device.
 */
object PlayerPreferences {

    private val store = createSettingsStore()

    /** Where to find libmpv, when neither the bundled nor an installed copy fits. */
    var libmpvPath: String?
        get() = store.getString(KEY_LIBMPV_PATH)
        set(value) {
            store.putString(KEY_LIBMPV_PATH, value?.takeIf { it.isNotBlank() })
            MpvNative.overridePath = value
        }

    var enhancement: VideoEnhancement
        get() = VideoEnhancement(
            superResolution = store.getString(KEY_SUPER_RESOLUTION) == "1",
            videoHdr = store.getString(KEY_VIDEO_HDR) == "1",
            scale = store.getString(KEY_SCALE)?.toIntOrNull() ?: 2
        )
        set(value) {
            store.putString(KEY_SUPER_RESOLUTION, if (value.superResolution) "1" else "0")
            store.putString(KEY_VIDEO_HDR, if (value.videoHdr) "1" else "0")
            store.putString(KEY_SCALE, value.scale.toString())
        }

    /** Applies the stored override before anything asks whether libmpv loaded. */
    fun install() {
        libmpvPath?.let { MpvNative.overridePath = it }
    }

    private const val KEY_LIBMPV_PATH = "player.libmpvPath"
    private const val KEY_SUPER_RESOLUTION = "player.rtxSuperResolution"
    private const val KEY_VIDEO_HDR = "player.rtxVideoHdr"
    private const val KEY_SCALE = "player.rtxScale"
}
