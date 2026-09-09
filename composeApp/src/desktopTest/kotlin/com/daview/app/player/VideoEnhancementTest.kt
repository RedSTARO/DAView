package com.daview.app.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two ways of asking mpv for NVIDIA's video features that fail silently:
 * a scaling mode with nothing to scale, and a filter string built for a
 * feature nobody asked for.
 */
class VideoEnhancementTest {

    @Test
    fun `nothing requested clears the filter chain`() {
        assertEquals("", VideoEnhancement().filterChain())
    }

    /**
     * `scaling-mode=nvidia` at scale 1 makes the filter decide no filtering is
     * needed; it never creates the video processor and never sets the driver
     * extension, so the picture is identical and nothing is logged. The scale
     * is therefore not optional and cannot be turned down to 1.
     */
    @Test
    fun `super resolution always carries a scale above one`() {
        val chain = VideoEnhancement(superResolution = true, scale = 1).filterChain()
        assertTrue("scale=1:" !in chain, "scale 1 would silently disable it: $chain")
        assertEquals("d3d11vpp=scale=2:scaling-mode=nvidia", chain)
    }

    @Test
    fun `an out of range scale is clamped rather than passed on`() {
        assertEquals(
            "d3d11vpp=scale=4:scaling-mode=nvidia",
            VideoEnhancement(superResolution = true, scale = 9).filterChain()
        )
    }

    /** HDR on its own forces the processor to exist, so it needs no upscale. */
    @Test
    fun `video hdr alone does not upscale`() {
        assertEquals(
            "d3d11vpp=nvidia-true-hdr=yes",
            VideoEnhancement(videoHdr = true).filterChain()
        )
    }

    @Test
    fun `both features share one filter`() {
        assertEquals(
            "d3d11vpp=scale=3:scaling-mode=nvidia:nvidia-true-hdr=yes",
            VideoEnhancement(superResolution = true, videoHdr = true, scale = 3).filterChain()
        )
    }

    /**
     * Observed on a hybrid laptop with the AMD adapter selected: mpv logs
     * "NVIDIA RTX Super Resolution enabled." there too, because the filter only
     * checks the HRESULT and a driver that does not know the GUID answers
     * success. Video HDR does probe, and said "not supported." in the same run.
     */
    @Test
    fun `enabled is not believed on a GPU it cannot be true on`() {
        assertEquals(
            EnhancementState.UNSUPPORTED,
            EnhancementLog.believable(EnhancementState.ACTIVE, "AMD Radeon 780M Graphics")
        )
        assertEquals(
            EnhancementState.ACTIVE,
            EnhancementLog.believable(EnhancementState.ACTIVE, "NVIDIA GeForce RTX 4060 Laptop GPU")
        )
        // Not yet known is no reason to contradict mpv.
        assertEquals(
            EnhancementState.ACTIVE,
            EnhancementLog.believable(EnhancementState.ACTIVE, null)
        )
        // Only "enabled" is in question; a failure is a failure anywhere.
        assertEquals(
            EnhancementState.FAILED,
            EnhancementLog.believable(EnhancementState.FAILED, "AMD Radeon 780M Graphics")
        )
    }

    /** The strings are mpv's own, from video/filter/vf_d3d11vpp.c. */
    @Test
    fun `mpv's log lines map onto what the player shows`() {
        assertEquals(
            EnhancementState.ACTIVE,
            EnhancementLog.superResolution("NVIDIA RTX Super Resolution enabled.")
        )
        assertEquals(
            EnhancementState.FAILED,
            EnhancementLog.superResolution("Failed to enable NVIDIA RTX Super Resolution: E_INVALIDARG")
        )
        assertEquals(
            EnhancementState.ACTIVE,
            EnhancementLog.videoHdr("NVIDIA RTX Video HDR enabled.")
        )
        assertEquals(
            EnhancementState.UNSUPPORTED,
            EnhancementLog.videoHdr("NVIDIA RTX Video HDR not supported.")
        )
        // Asking for HDR on an HDR source is not a failure; there is simply
        // nothing to convert.
        assertEquals(
            EnhancementState.UNSUPPORTED,
            EnhancementLog.videoHdr("NVIDIA RTX Video HDR requested, but the source is already HDR, not used.")
        )
        assertEquals(null, EnhancementLog.videoHdr("Using hardware decoding (d3d11va)."))
        assertTrue(
            EnhancementLog.isMaxLumaGuess(
                "Tagging image output as HDR with max-luma=1000 nits for NVIDIA RTX Video HDR."
            )
        )
    }
}
