package com.daview.app.player

/**
 * NVIDIA's two video features, as mpv expresses them.
 *
 * Their product names are **RTX Video Super Resolution** and **RTX Video HDR**.
 * They are not DLSS: DLSS needs motion vectors and depth from a render pipeline,
 * which a decoded video frame does not have, and NVIDIA states outright that
 * video super resolution does not use it. Both are reached through the same
 * mechanism — two undocumented driver GUIDs passed to
 * `ID3D11VideoContext::VideoProcessorSetStreamExtension` — which mpv wraps in
 * the `d3d11vpp` filter, so DAView only has to build a filter string.
 */
data class VideoEnhancement(
    val superResolution: Boolean = false,
    val videoHdr: Boolean = false,
    /** Upscale factor applied by the video processor. Only meaningful with [superResolution]. */
    val scale: Int = 2
) {
    val enabled: Boolean get() = superResolution || videoHdr

    /**
     * The `vf` value, or an empty string to clear the chain.
     *
     * Two rules are encoded here rather than left to the user, because getting
     * either wrong fails silently:
     *
     *  - `scaling-mode=nvidia` on its own does nothing. At `scale=1.0`, with no
     *    format change and no true-hdr, the filter decides no filtering is
     *    required, never creates the video processor, and never sets the driver
     *    extension. So super resolution always carries a scale above 1.
     *  - `nvidia-true-hdr=yes` does force the processor to exist, so HDR alone
     *    needs no scale — and asking for one would upscale for no reason.
     */
    fun filterChain(): String {
        if (!enabled) return ""
        val options = buildList {
            if (superResolution) {
                add("scale=${scale.coerceIn(MIN_SCALE, MAX_SCALE)}")
                add("scaling-mode=nvidia")
            }
            if (videoHdr) add("nvidia-true-hdr=yes")
        }
        return "d3d11vpp=" + options.joinToString(":")
    }

    companion object {
        const val MIN_SCALE = 2
        const val MAX_SCALE = 4
    }
}

/** What mpv said about the enhancement, in terms the player screen can show. */
enum class EnhancementState { OFF, REQUESTED, ACTIVE, UNSUPPORTED, FAILED }

/**
 * Reads mpv's own log lines back.
 *
 * There is no property to ask. `d3d11vpp` only reports what happened by
 * logging it, and for super resolution it does not even probe the driver — it
 * checks the `HRESULT` and nothing else. So "active" here means mpv set the
 * extension without error, which is as much as anything in this stack knows.
 *
 * The strings are matched as prefixes of mpv's own format strings in
 * `video/filter/vf_d3d11vpp.c`; matching the whole line would break on the
 * ones that interpolate an `HRESULT`.
 */
object EnhancementLog {

    private const val SR_OK = "NVIDIA RTX Super Resolution enabled."
    private const val SR_FAILED = "Failed to enable NVIDIA RTX Super Resolution"
    private const val HDR_OK = "NVIDIA RTX Video HDR enabled."
    private const val HDR_FAILED = "Failed to enable NVIDIA RTX Video HDR"
    private const val HDR_UNSUPPORTED = "NVIDIA RTX Video HDR not supported."
    private const val HDR_SOURCE_IS_HDR = "NVIDIA RTX Video HDR requested, but the source is"

    fun superResolution(line: String): EnhancementState? = when {
        line.contains(SR_OK) -> EnhancementState.ACTIVE
        line.contains(SR_FAILED) -> EnhancementState.FAILED
        else -> null
    }

    fun videoHdr(line: String): EnhancementState? = when {
        line.contains(HDR_OK) -> EnhancementState.ACTIVE
        line.contains(HDR_FAILED) -> EnhancementState.FAILED
        // Both of these are "asked for, will not happen": no probe support on
        // this GPU, or an HDR source that has nothing to convert.
        line.contains(HDR_UNSUPPORTED) -> EnhancementState.UNSUPPORTED
        line.contains(HDR_SOURCE_IS_HDR) -> EnhancementState.UNSUPPORTED
        else -> null
    }

    /**
     * mpv guesses 1000 nits when it retags the frame as HDR10 and says so. The
     * guess is worth passing on, because the right value depends on a slider in
     * the NVIDIA app that nothing here can read.
     */
    fun isMaxLumaGuess(line: String): Boolean =
        line.contains("Tagging image output as HDR with max-luma=")

    /**
     * Discounts "enabled" on a GPU where it cannot be true.
     *
     * Super resolution has no capability probe at all — mpv sets the driver
     * extension and checks the `HRESULT`, nothing more. Observed on a hybrid
     * laptop with the AMD adapter selected: mpv logs "NVIDIA RTX Super
     * Resolution enabled." and the picture is untouched, because a driver that
     * does not know the GUID answers success anyway. Video HDR does probe, and
     * correctly reported "not supported." in the same run.
     *
     * [adapter] null means the adapter is not known yet, and an unknown adapter
     * is no reason to contradict mpv.
     */
    fun believable(state: EnhancementState, adapter: String?): EnhancementState =
        if (state == EnhancementState.ACTIVE && adapter != null &&
            !adapter.contains("NVIDIA", ignoreCase = true)
        ) {
            EnhancementState.UNSUPPORTED
        } else {
            state
        }
}
