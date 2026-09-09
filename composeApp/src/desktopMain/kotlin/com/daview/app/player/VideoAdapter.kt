package com.daview.app.player

/**
 * Which GPU mpv renders on.
 *
 * It matters more than it looks. On a laptop with a discrete card the display
 * is usually driven by the integrated one, and NVIDIA's video extensions live
 * on the NVIDIA adapter — ask for them anywhere else and the driver simply
 * fails the call. So this used to be hardcoded to `NVIDIA` whenever either RTX
 * feature was on, which is right for that machine and an assumption everywhere
 * else.
 *
 * mpv matches the value as a **case-insensitive prefix of the adapter
 * description** (`bstr_case_startswith` in `d3d11_helpers.c`) and picks the
 * first adapter that matches, so a vendor name is a legitimate value and a full
 * description is simply a more specific one.
 */
object VideoAdapter {

    /**
     * What to offer before anything is known about the machine.
     *
     * These are prefixes, not names: every one of them is checked against the
     * real adapters below, and only the ones this machine actually has are
     * shown. `Microsoft Basic Render Driver` is WARP — software rendering, and
     * a terrible choice for video — but it is a real adapter and hiding an
     * option the user can see in Device Manager would be its own confusion.
     */
    private val CANDIDATES = listOf("NVIDIA", "AMD", "Intel", "Qualcomm", "Microsoft Basic Render Driver")

    /**
     * The candidates this machine really has.
     *
     * There is no way to ask libmpv for the list: `--d3d11-adapter=help` prints
     * it through the option parser's own log, which the client API never sees —
     * verified, the call returns an error and not one log message arrives. What
     * *is* reliable is the validator behind the same option, which rejects a
     * name matching no adapter at the moment it is set. So the list is built by
     * asking rather than by reading.
     *
     * One throwaway mpv instance answers for all of them; it is never
     * initialised, so nothing is opened and no window appears.
     */
    fun available(): List<String> {
        if (!MpvNative.isWindows) return emptyList()
        val mpv = MpvNative.library() ?: return emptyList()
        val handle = mpv.mpv_create() ?: return emptyList()
        return try {
            CANDIDATES.filter { mpv.mpv_set_option_string(handle, OPTION, it) >= 0 }
        } finally {
            runCatching { mpv.mpv_terminate_destroy(handle) }
        }
    }

    /** Whether mpv would accept [name], so a typed-in value can be refused up front. */
    fun accepts(name: String): Boolean {
        if (!MpvNative.isWindows || name.isBlank()) return false
        val mpv = MpvNative.library() ?: return false
        val handle = mpv.mpv_create() ?: return false
        return try {
            mpv.mpv_set_option_string(handle, OPTION, name) >= 0
        } finally {
            runCatching { mpv.mpv_terminate_destroy(handle) }
        }
    }

    /**
     * The adapter mpv reports having picked, out of one of its log lines.
     *
     * This is the only place the *full* description is ever visible from inside
     * the app, and it is what someone would need in order to name one of two
     * cards from the same vendor. Recording it is also the only way to answer
     * "which GPU is this actually running on" after the fact.
     */
    fun deviceNameFrom(prefix: String, text: String): String? {
        // libplacebo announces the same device a line later, indented and from
        // its own module; taking that one too would be harmless but taking it
        // *instead* would depend on log ordering, so the D3D11 context is the
        // single source.
        if (!prefix.contains("d3d11")) return null
        if (!text.startsWith(DEVICE_NAME_PREFIX)) return null
        return text.removePrefix(DEVICE_NAME_PREFIX).trim().takeIf { it.isNotEmpty() }
    }

    const val OPTION = "d3d11-adapter"
    private const val DEVICE_NAME_PREFIX = "Device Name: "
}
