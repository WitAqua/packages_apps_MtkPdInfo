/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.mtk.pd_info.source

import org.witaqua.mtk.pd_info.io.DirectSysfs
import org.witaqua.mtk.pd_info.io.RootSysfs
import org.witaqua.mtk.pd_info.io.Sysfs
import org.witaqua.mtk.pd_info.model.Snapshot

/**
 * Picking where to read from.
 *
 * There are two interfaces and they are not alternatives: MediaTek's port
 * controller is the only one that carries an object list, and the charging
 * stack is the only one that says what kind of contract was reached. So the
 * order here is not a search for the better of the two - it is the object list
 * first, and what the charger says laid over whichever is found.
 *
 * Unlike the qualcomm boards this was grown from, no kernel version enters
 * into it. The tcpc class is MediaTek's own and has been the same since the
 * 4.x kernels; the upstream usb_power_delivery class is present on these
 * boards and always empty, because nothing in rt_pd_manager.c registers a
 * device with it. Presence is the test, and where the port controller cannot
 * be read it is a labelling question rather than a kernel one.
 */
object Sources {
    /**
     * The interfaces to try, best first. Exposed rather than private so the
     * screen can say what was considered when nothing is found.
     */
    fun candidates(): List<PdSource> = listOf(TcpcSource, ChargerSource)

    /**
     * Reads through [sysfs], trying each candidate in turn. A source that is
     * present but yields nothing usable does not stop the search - except when
     * it says why it is empty, which is an answer rather than a miss.
     */
    fun read(sysfs: Sysfs): Snapshot? {
        var empty: Snapshot? = null

        for (source in candidates()) {
            if (!source.present(sysfs)) {
                continue
            }

            val snapshot = source.read(sysfs)?.let { withCharger(sysfs, it) } ?: continue
            if (snapshot.hasAnything && snapshot.emptyReason == null) {
                return snapshot
            }

            /*
             * Keep the first interface that could explain itself, in case
             * nothing better turns up. "A cable that negotiated nothing" is
             * worth telling the reader; "nothing here" is not.
             */
            if (empty == null) {
                empty = snapshot
            }
        }

        return empty
    }

    /**
     * What the port controller cannot say, and what nothing published by
     * either of them is: the charging stack's reading of the adapter, and the
     * charger's own measurement of the bus.
     *
     * The kind of contract is the part worth having. A programmable supply is
     * requested against an object like any other, so the object list alone
     * cannot say whether the one in force is being held at a fixed voltage or
     * stepped - PE_READY_SNK_APDO is a state of the policy engine, and
     * pd_type is where it surfaces.
     */
    private fun withCharger(sysfs: Sysfs, snapshot: Snapshot): Snapshot {
        val adapter = snapshot.adapter ?: MtkCharger.adapter(sysfs)
        val protocol = MtkCharger.protocol(adapter)

        /*
         * Laid over a port only where there is one port to be wrong about:
         * this reading has no port in it, and a board with two sockets can
         * hold a charger on one and a data cable on the other.
         */
        val ports = if (snapshot.ports.size == 1) {
            snapshot.ports.map { port ->
                port.copy(
                    protocol = port.protocol ?: protocol,
                    programmable = port.programmable ?: adapter?.connection?.programmable,
                )
            }
        } else {
            snapshot.ports
        }

        return snapshot.copy(
            ports = ports,
            adapter = adapter,
            measured = snapshot.measured ?: ChargerSupply.measured(sysfs),
        )
    }

    /**
     * The way in to use. Reading the files directly is right when the device
     * tree labelled them for this app, which is the case in a ROM build and
     * generally not otherwise; a root shell is the fallback rather than the
     * default, because it costs a process per batch and may prompt.
     *
     * Presence, not a full read, decides: if the port controller can be seen
     * without help then the reads will work too, and if it cannot then no
     * amount of trying changes it.
     */
    fun sysfs(preferRoot: Boolean = false): Sysfs {
        if (!preferRoot &&
            candidates().any { it.publishesCapabilities && it.present(DirectSysfs) }
        ) {
            return DirectSysfs
        }
        return if (RootSysfs.available()) RootSysfs else DirectSysfs
    }
}
