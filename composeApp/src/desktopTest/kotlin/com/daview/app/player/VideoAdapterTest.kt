package com.daview.app.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading the adapter back out of mpv's log is the only way the app ever learns
 * the full description, and the same sentence is emitted twice by two different
 * modules — so which one is read is not a detail.
 */
class VideoAdapterTest {

    @Test
    fun `the d3d11 context's line names the adapter`() {
        assertEquals(
            "NVIDIA GeForce RTX 4060 Laptop GPU",
            VideoAdapter.deviceNameFrom(
                "vo/gpu-next/d3d11",
                "Device Name: NVIDIA GeForce RTX 4060 Laptop GPU"
            )
        )
    }

    /**
     * libplacebo repeats it a line later, indented and under its own module.
     * Reading that one instead would make the result depend on log ordering.
     */
    @Test
    fun `libplacebo's copy of the same line is ignored`() {
        assertNull(
            VideoAdapter.deviceNameFrom(
                "vo/gpu-next/libplacebo",
                "    Device Name: NVIDIA GeForce RTX 4060 Laptop GPU"
            )
        )
    }

    @Test
    fun `other d3d11 lines are not device names`() {
        assertNull(VideoAdapter.deviceNameFrom("vo/gpu-next/d3d11", "Using Direct3D 11 feature level 12_1"))
        assertNull(VideoAdapter.deviceNameFrom("vo/gpu-next/d3d11", "Device ID: 10de:28e0 (rev a1)"))
        assertNull(VideoAdapter.deviceNameFrom("vo/gpu-next/d3d11", "Device Name: "))
    }
}
