/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.mtk.pd_info.source

import org.witaqua.mtk.pd_info.io.Sysfs
import org.witaqua.mtk.pd_info.model.EmptyReason
import org.witaqua.mtk.pd_info.model.Origin
import org.witaqua.mtk.pd_info.model.Port
import org.witaqua.mtk.pd_info.model.Snapshot

/*
 * The type-C class and the charging stack, which between them describe the
 * contract and carry no object list at all.
 *
 * This is what is left on a board whose port controller class is not readable:
 * the roles and the revision from drivers/usb/typec/class.c, and what kind of
 * contract it is from the charger. It cannot answer the question this app is
 * for - nothing here is an object list - but it is the difference between a
 * screen that says a 33W programmable contract is in force and one that says
 * nothing at all.
 *
 * It is also the whole story on a board where /sys/class/tcpc is labelled for
 * the vendor's own charging service and not for this app: see the sepolicy
 * section of README.md, which is the part that is actually device work.
 */
object ChargerSource : PdSource {
    override fun present(sysfs: Sysfs) =
        TypeCClass.ports(sysfs).isNotEmpty() || MtkCharger.adapter(sysfs) != null

    /**
     * No object list here, ever. Saying so keeps this from settling which way
     * in to use: a root shell that would have got the port controller's
     * objects is worth taking even though this much reads without one.
     */
    override val publishesCapabilities = false

    override fun read(sysfs: Sysfs): Snapshot? {
        val adapter = MtkCharger.adapter(sysfs)
        val protocol = MtkCharger.protocol(adapter)

        val ports = TypeCClass.ports(sysfs).map { name ->
            val port = TypeCClass.port(sysfs, name)

            /*
             * The charger reading is one view of "the" charger with no port in
             * it, so it is only laid over a port where there is one port to be
             * wrong about.
             */
            if (TypeCClass.ports(sysfs).size != 1) {
                port
            } else {
                port.copy(
                    protocol = protocol,
                    programmable = adapter?.connection?.let { it.programmable },
                )
            }
        }

        if (ports.isEmpty() && adapter == null) {
            return null
        }

        return Snapshot(
            origin = Origin.CHARGER,
            ports = ports,
            adapter = adapter,
            emptyReason = when {
                ports.isNotEmpty() && ports.none { it.attached } -> EmptyReason.NOTHING_ATTACHED
                else -> EmptyReason.NO_OBJECT_INTERFACE
            },
        )
    }
}

/** The port a snapshot is about, where one of them is more interesting. */
internal fun List<Port>.principal(): Port? =
    firstOrNull { it.request != null }
        ?: firstOrNull { it.capabilities.isNotEmpty() }
        ?: firstOrNull { it.attached }
        ?: firstOrNull()
