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
 * agreed. A MediaTek board has several to choose between and they do not agree
 * on units or even on names: Xiaomi's fork registers "usb", MediaTek's own
 * framework does not and leaves the charger chip's own supply - "primary_chg"
 * - as the one that sees the bus, beside "mtk-master-charger" and the charge
 * pumps.
 *
 * So the pick is by what a supply answers rather than by what it is called:
 * online, not the battery, and carrying a reading. "usb" first where it is
 * there, then anything calling itself a USB supply, then whatever is left -
 * which is how a board with MediaTek's own charger gets a measurement at all.
 */
internal object ChargerSupply {
    fun measured(sysfs: Sysfs): Measured? =
        sysfs.list(POWER_SUPPLY)
            .sortedBy { rank(sysfs, it) }
            .firstNotNullOfOrNull { name ->
                val directory = "$POWER_SUPPLY/$name"
                val values = sysfs.read(
                    listOf("type", "online", "voltage_now", "current_now")
                        .map { "$directory/$it" }
                )

                val type = values["$directory/type"] ?: return@firstNotNullOfOrNull null
                /*
                 * The battery is the one supply that is always online and
                 * always has a voltage, and it is never the answer: what it
                 * measures is the cell rather than the bus.
                 */
                if (type == BATTERY || values["$directory/online"] != "1") {
                    return@firstNotNullOfOrNull null
                }

                val millivolts = values["$directory/voltage_now"].milli()
                val milliamps = values["$directory/current_now"].milli()
                if (millivolts == null && milliamps == null) {
                    return@firstNotNullOfOrNull null
                }

                Measured(name, type, millivolts, milliamps)
            }

    /* "usb" first, then anything that says it is one, then the rest. */
    private fun rank(sysfs: Sysfs, name: String): Int = when {
        name == PREFERRED -> 0
        sysfs.read("$POWER_SUPPLY/$name/type")?.startsWith(USB) == true -> 1
        else -> 2
    }

    private const val PREFERRED = "usb"
    private const val USB = "USB"
    private const val BATTERY = "Battery"

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
