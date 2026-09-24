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
 * The charging stack, through the attributes drivers/power/supply/mtk_charger.c
 * hangs off the USB power supply:
 *
 *   /sys/class/power_supply/usb/real_type           "USB_PD", "SDP", "DCP"
 *   /sys/class/power_supply/usb/pd_type             enum mtk_pd_connect_type
 *   /sys/class/power_supply/usb/quick_charge_type   enum quick_charge_type
 *   /sys/class/power_supply/usb/apdo_max            watts
 *   /sys/class/power_supply/usb/power_max           watts
 *   /sys/class/power_supply/usb/pd_authentication   Xiaomi's adapter check
 *
 * They are one sysfs group registered by usb_sysfs_create_group(), so a board
 * has all of them or none. Xiaomi's tree is where this shape comes from;
 * MediaTek's own reference charger publishes the same pd_type through the
 * adapter class, which is the one attribute here that matters for reading a
 * contract rather than for the charging animation.
 *
 * pd_type is the MediaTek answer to a question the wire format does not settle:
 * whether the contract is against a programmable supply. The port controller
 * knows - PE_READY_SNK_APDO is a state of its policy engine - and this is
 * where that state surfaces. Worth one read even on a board whose object list
 * is readable, because it agrees or disagrees with what the objects say.
 */
internal object MtkCharger {
    private const val SUPPLY = "$POWER_SUPPLY/usb"

    private val ATTRIBUTES = listOf(
        "real_type",
        "pd_type",
        "quick_charge_type",
        "apdo_max",
        "power_max",
        "pd_authentication",
    )

    /** What the charging stack made of the adapter, or null where it is silent. */
    fun adapter(sysfs: Sysfs): Adapter? {
        val values = sysfs.read(ATTRIBUTES.map { "$SUPPLY/$it" })
        if (values.isEmpty()) {
            return null
        }

        fun value(name: String) = values["$SUPPLY/$name"]
        fun number(name: String) = value(name)?.toIntOrNull()

        val adapter = Adapter(
            realType = value("real_type")?.takeIf { it != UNKNOWN },
            connection = PdConnection.of(number("pd_type")),
            quickCharge = QuickCharge.of(number("quick_charge_type")),
            /* Zero is "nothing on offer" rather than a nought-watt supply. */
            apdoMaxWatts = number("apdo_max")?.takeIf { it > 0 },
            powerMaxWatts = number("power_max")?.takeIf { it > 0 },
            authenticated = number("pd_authentication")?.let { it == 1 },
        )

        return adapter.takeIf {
            it.realType != null || it.connection != null || it.powerMaxWatts != null
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
