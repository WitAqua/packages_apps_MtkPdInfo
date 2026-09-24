/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.mtk.pd_info.source

import org.witaqua.mtk.pd_info.io.Sysfs
import org.witaqua.mtk.pd_info.model.Measured

/** Where the charger's own readings live. */
internal const val POWER_SUPPLY = "/sys/class/power_supply"

/*
 * The charger's supply, which measures the port rather than saying what was
 * agreed. On a MediaTek board there are several to choose between - "usb",
 * "mtk-master-charger", the charge pumps - and they do not agree on units, so
 * this takes the first one that is online and says it is a USB supply, which
 * is "usb" on every board checked.
 */
internal object ChargerSupply {
    fun measured(sysfs: Sysfs): Measured? =
        sysfs.list(POWER_SUPPLY)
            .sortedBy { if (it == PREFERRED) 0 else 1 }
            .firstNotNullOfOrNull { name ->
                val directory = "$POWER_SUPPLY/$name"
                val values = sysfs.read(
                    listOf("type", "online", "voltage_now", "current_now")
                        .map { "$directory/$it" }
                )

                val type = values["$directory/type"] ?: return@firstNotNullOfOrNull null
                if (!type.startsWith("USB") || values["$directory/online"] != "1") {
                    return@firstNotNullOfOrNull null
                }

                val millivolts = values["$directory/voltage_now"].milli()
                val milliamps = values["$directory/current_now"].milli()
                if (millivolts == null && milliamps == null) {
                    return@firstNotNullOfOrNull null
                }

                Measured(name, type, millivolts, milliamps)
            }

    private const val PREFERRED = "usb"

    /*
     * The power supply class documents these as micro units, and MediaTek's
     * charger does not follow it: /sys/class/power_supply/usb/voltage_now
     * reads 5081 where the bus is at 5.081V, while the battery supply beside
     * it reads 4434000 for 4.434V. Both spellings have to be read, and the
     * only thing that tells them apart is size - a USB bus does not reach 100V
     * on any revision of the specification, so anything above that many
     * millivolts is micro units.
     */
    private fun String?.milli(): Int? {
        val value = this?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return if (value > MICRO_THRESHOLD) value / 1000 else value
    }

    private const val MICRO_THRESHOLD = 100_000
}
