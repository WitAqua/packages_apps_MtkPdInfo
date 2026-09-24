/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.mtk.pd_info

import android.os.Build
import android.system.Os

/**
 * What this handset is. Unlike the qualcomm sibling of this app, none of it
 * decides where to read from: MediaTek's port controller class is the same on
 * every board that has it and has been since the 4.x kernels, so presence is
 * the only test worth making - see [org.witaqua.mtk.pd_info.source.Sources].
 *
 * It is here for the screen that has nothing to show, which is the one place
 * where saying what the board is helps somebody work out why.
 */
object Platform {
    /** Kernel release, as uname reports it: "6.12.38-android16-5-g1d46..." */
    val kernelRelease: String by lazy {
        try {
            Os.uname().release ?: ""
        } catch (e: Exception) {
            System.getProperty("os.version") ?: ""
        }
    }

    /**
     * The SoC, preferring the board platform the vendor image sets - "mt6993",
     * "mt6991" - and falling back to what Build exposes. Lower case throughout.
     */
    val platform: String by lazy {
        sequenceOf(
            Build.SOC_MODEL.takeIf { it != Build.UNKNOWN },
            Build.BOARD.takeIf { it != Build.UNKNOWN },
            Build.HARDWARE,
        ).filterNotNull().firstOrNull { it.isNotBlank() }?.lowercase() ?: ""
    }

    val manufacturer: String by lazy { Build.SOC_MANUFACTURER.lowercase() }

    /**
     * Whether this looks like a MediaTek part. Nothing branches on it - the
     * probe does that - but a board that is not one is worth saying so on the
     * screen that found nothing, since this app would then be the wrong one
     * to have opened.
     */
    val looksMediatek: Boolean by lazy {
        manufacturer.contains("mediatek") ||
            manufacturer == "mtk" ||
            platform.startsWith("mt") ||
            platform.startsWith("dimensity")
    }
}
