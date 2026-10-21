package com.wireguard.android.backend

import android.util.Log

object GoBackend {
    private const val TAG = "GoBackend"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("wg-go")
            isLoaded = true
            Log.d(TAG, "WireGuard Go library loaded successfully, version: " + runCatching { wgVersion() }.getOrDefault("unknown"))
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load libwg-go.so: ${t.message}", t)
        }
    }

    fun isLibraryLoaded(): Boolean = isLoaded

    @JvmStatic
    external fun wgTurnOn(ifName: String, tunFd: Int, settings: String): Int

    @JvmStatic
    external fun wgTurnOff(handle: Int)

    @JvmStatic
    external fun wgGetSocketV4(handle: Int): Int

    @JvmStatic
    external fun wgGetSocketV6(handle: Int): Int

    @JvmStatic
    external fun wgGetConfig(handle: Int): String?

    @JvmStatic
    external fun wgVersion(): String
}
