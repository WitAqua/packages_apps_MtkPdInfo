/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.mtk.pd_info.source

import org.witaqua.mtk.pd_info.io.Sysfs
import org.witaqua.mtk.pd_info.model.Adapter
import org.witaqua.mtk.pd_info.model.PdConnection
import org.witaqua.mtk.pd_info.model.QuickCharge

/*
 * The charging stack, which is the only thing on a MediaTek board that says
 * how far the power delivery handshake got. It publishes that in one of two
 * places, and which one a board has follows whose charger driver it took.
 *
 * Xiaomi's fork hangs a group of its own off the USB power supply, from
 * usb_sysfs_create_group() in drivers/power/supply/mtk_charger.c:
 *
 *   /sys/class/power_supply/usb/real_type          "USB_PD", "SDP", "DCP", ...
 *   /sys/class/power_supply/usb/pd_type            enum mtk_pd_connect_type
 *   /sys/class/power_supply/usb/quick_charge_type  enum quick_charge_type
 *   /sys/class/power_supply/usb/apdo_max           watts
 *   /sys/class/power_supply/usb/power_max          watts
 *   /sys/class/power_supply/usb/pd_authentication  the vendor's adapter check
 *
 * MediaTek's own framework puts the same question's answer on the charger
 * platform device instead, and has no USB supply at all:
 *
 *   /sys/devices/platform/charger/pd_type          the same enum
 *   /sys/devices/platform/charger/chr_type         BC1.2 detection's answer
 *   /sys/devices/platform/charger/charge_rate      where the vendor added one
 *
 * Both are read, because a board has one or the other and nothing says which
 * from the outside. pd_type is what matters either way: it is the only thing
 * that distinguishes a programmable contract from a fixed one without reading
 * the objects, and where the objects are readable it agrees or disagrees with
 * them.
 */
internal object MtkCharger {
    /* Xiaomi's fork, on the USB supply. */
    private const val SUPPLY = "$POWER_SUPPLY/usb"

    /* MediaTek's own, on the charger platform device. */
    private const val CHARGER = "/sys/devices/platform/charger"

    private val SUPPLY_ATTRIBUTES = listOf(
        "real_type",
        "pd_type",
        "quick_charge_type",
        "apdo_max",
        "power_max",
        "pd_authentication",
    )

    private val CHARGER_ATTRIBUTES = listOf(
        "pd_type",
        "chr_type",
        "charge_rate",
    )

    /** What the charging stack made of the adapter, or null where it is silent. */
    fun adapter(sysfs: Sysfs): Adapter? {
        val values = sysfs.read(
            SUPPLY_ATTRIBUTES.map { "$SUPPLY/$it" } + CHARGER_ATTRIBUTES.map { "$CHARGER/$it" }
        )
        if (values.isEmpty()) {
            return null
        }

        fun supply(name: String) = values["$SUPPLY/$name"]
        fun charger(name: String) = values["$CHARGER/$name"]
        fun number(value: String?) = value?.toIntOrNull()

        val adapter = Adapter(
            /*
             * The fork's own name for the adapter where there is one, else
             * what BC1.2 detection called it. chr_type is the coarser of the
             * two - it does not know a PD contract from a plain charger - but
             * the handshake below says that part.
             */
            realType = (supply("real_type") ?: charger("chr_type"))
                ?.takeIf { it != UNKNOWN },
            connection = PdConnection.of(number(supply("pd_type") ?: charger("pd_type"))),
            quickCharge = QuickCharge.of(number(supply("quick_charge_type"))),
            rate = charger("charge_rate"),
            /* Zero is "nothing on offer" rather than a nought-watt supply. */
            apdoMaxWatts = number(supply("apdo_max"))?.takeIf { it > 0 },
            powerMaxWatts = number(supply("power_max"))?.takeIf { it > 0 },
            authenticated = number(supply("pd_authentication"))?.let { it == 1 },
        )

        return adapter.takeIf {
            it.realType != null || it.connection != null ||
                it.powerMaxWatts != null || it.rate != null
        }
    }

    /**
     * The protocol in force, in the charging stack's words. The connection
     * state is the better of the two answers - it comes from the policy engine
     * rather than from BC1.2 detection - and the adapter type is what is left
     * when power delivery is not what is happening.
     */
    fun protocol(adapter: Adapter?): String? =
        when (adapter?.connection) {
            PdConnection.READY_APDO -> PPS
            PdConnection.READY, PdConnection.READY_PD30,
            PdConnection.NEW_SOURCE_CAPABILITIES,
            -> PD

            PdConnection.TYPEC_ONLY -> adapter.realType ?: TYPE_C
            else -> adapter?.realType
        }

    private const val UNKNOWN = "Unknown"
    private const val PD = "PD"
    private const val PPS = "PPS"
    private const val TYPE_C = "Type-C"
}
