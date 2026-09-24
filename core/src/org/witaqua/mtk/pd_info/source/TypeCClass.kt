/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.mtk.pd_info.source

import org.witaqua.mtk.pd_info.io.Sysfs
import org.witaqua.mtk.pd_info.model.Port

/*
 * The type-C class, drivers/usb/typec/class.c. MediaTek registers with it from
 * rt_pd_manager.c - typec_register_port() for the socket, and a partner device
 * when something is plugged into it - which is the only part of this stack
 * that is upstream shaped.
 *
 * It carries the roles, the revision and no data objects at all, so it is
 * never a source on its own: it is the half of a port that both readers here
 * agree on, read in one place rather than in each of them.
 *
 * What it does not carry, on these boards, is a usb_power_delivery device.
 * rt_pd_manager.c never calls usb_power_delivery_register(), so
 * /sys/class/usb_power_delivery exists and holds nothing - checked on the
 * handset, and the reason [TcpcSource] is where the objects come from.
 */
internal object TypeCClass {
    const val DIRECTORY = "/sys/class/typec"
    const val PARTNER_SUFFIX = "-partner"

    /**
     * The ports, without the partner and cable devices that sit beside them in
     * the same class: those all carry a dash and a port never does.
     */
    fun ports(sysfs: Sysfs): List<String> = sysfs.list(DIRECTORY).filterNot { it.contains('-') }

    /**
     * What the class says about one port. Anything a data object would add is
     * left for the caller to fill in.
     */
    fun port(sysfs: Sysfs, name: String): Port {
        val directory = "$DIRECTORY/$name"
        val partner = "$directory$PARTNER_SUFFIX"

        val values = sysfs.read(
            listOf(
                "$directory/power_role",
                "$directory/data_role",
                "$directory/power_operation_mode",
                "$directory/usb_power_delivery_revision",
                "$partner/supports_usb_power_delivery",
            )
        )

        return Port(
            /*
             * A partner device is what the class grows when something is
             * plugged in and drops when it comes out, which is the plainest
             * signal for that there is.
             */
            attached = sysfs.list(DIRECTORY).contains("$name$PARTNER_SUFFIX"),
            name = name,
            powerRole = values["$directory/power_role"]?.activeValue(),
            dataRole = values["$directory/data_role"]?.activeValue(),
            contract = values["$directory/power_operation_mode"],
            pdRevision = values["$directory/usb_power_delivery_revision"],
            partnerSupportsPd = values["$partner/supports_usb_power_delivery"]?.equals("yes"),
        )
    }
}
