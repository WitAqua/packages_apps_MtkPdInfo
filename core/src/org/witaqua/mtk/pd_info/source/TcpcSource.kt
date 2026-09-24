/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.mtk.pd_info.source

import org.witaqua.mtk.pd_info.io.Sysfs
import org.witaqua.mtk.pd_info.model.EmptyReason
import org.witaqua.mtk.pd_info.model.Origin
import org.witaqua.mtk.pd_info.model.Port
import org.witaqua.mtk.pd_info.model.PowerDeliveryObject
import org.witaqua.mtk.pd_info.model.Request
import org.witaqua.mtk.pd_info.model.Snapshot
import org.witaqua.mtk.pd_info.model.SourceCapability

/*
 * MediaTek's port controller class, drivers/misc/mediatek/typec/tcpc, which is
 * the whole power delivery stack on these boards: the policy engine, the
 * device policy manager and the type-C state machine all live in it, and it
 * has carried the same class from the 4.x kernels through android16-6.12.
 *
 *   /sys/class/tcpc/<port>/caps_info    both ends' objects, already decoded,
 *                                       and the object position in force
 *   /sys/class/tcpc/<port>/pe_ready     "yes" once a contract is reached
 *   /sys/class/tcpc/<port>/typec_role   how the port is set up to behave
 *   /sys/class/tcpc/<port>/vbus_level   whether the bus is up
 *
 * The port is named after the device tree node - "type_c_port0" on every
 * Xiaomi board checked - and the type-C class names the same port "port0",
 * which is what the roles and the revision come from. See [TypeCClass].
 *
 * This is the one interface on a MediaTek board that carries an object list at
 * all: the upstream usb_power_delivery class is registered by the kernel and
 * left empty, because rt_pd_manager.c registers the port with the type-C class
 * and never calls usb_power_delivery_register(). docs/kernel.md has the rest.
 */
object TcpcSource : PdSource {
    private const val CLASS = "/sys/class/tcpc"

    override fun present(sysfs: Sysfs) =
        sysfs.list(CLASS).any { sysfs.read("$CLASS/$it/caps_info") != null }

    override fun read(sysfs: Sysfs): Snapshot? {
        val names = sysfs.list(CLASS)
        if (names.isEmpty()) {
            return null
        }

        val ports = names.mapNotNull { name -> port(sysfs, name) }
        if (ports.isEmpty()) {
            return null
        }

        return Snapshot(
            origin = Origin.TCPC,
            ports = ports,
            emptyReason = when {
                ports.none { it.attached } -> EmptyReason.NOTHING_ATTACHED
                ports.none { it.capabilities.isNotEmpty() } -> EmptyReason.NO_CONTRACT
                else -> null
            },
        )
    }

    private fun port(sysfs: Sysfs, name: String): Port? {
        val directory = "$CLASS/$name"

        /*
         * One batch for the whole port: with a root shell every call is a
         * process, and the type-C class costs a few more below.
         */
        val values = sysfs.read(
            listOf("caps_info", "pe_ready", "vbus_level", "typec_role", "role_def")
                .map { "$directory/$it" }
        )

        val caps = values["$directory/caps_info"]?.let { CapsInfo.parse(it) } ?: return null

        /*
         * The same port under the name the type-C class gives it, for the
         * roles and the revision. Absent where the class is not readable,
         * which is a labelling question rather than a missing port - the
         * objects above are the part worth having either way.
         */
        val typec = typecName(name)?.let { TypeCClass.port(sysfs, it) }

        return Port(
            /*
             * A partner device is what the type-C class grows when something
             * is plugged in and drops when it comes out, which is the plainest
             * signal for that there is. Where the class cannot be read, a bus
             * with voltage on it is the next best.
             */
            attached = typec?.attached
                ?: (values["$directory/vbus_level"]?.toIntOrNull()?.let { it > 0 } ?: false),
            name = typec?.name ?: name,
            powerRole = typec?.powerRole,
            dataRole = typec?.dataRole,
            /*
             * The policy engine's own word for it. pe_ready is set when the
             * sink reaches PE_SNK_Ready, which is the point a contract exists.
             */
            contract = values["$directory/pe_ready"]?.let {
                if (it == "yes") EXPLICIT else IMPLICIT
            },
            pdRevision = typec?.pdRevision,
            partnerSupportsPd = typec?.partnerSupportsPd,
            /*
             * Two spellings of the same attribute: the newer trees call it
             * typec_role, the ones these phones shipped with call it role_def.
             */
            roleDefinition = values["$directory/typec_role"]
                ?: values["$directory/role_def"],
            capabilities = caps.remoteSource,
            sinkCapabilities = caps.localSink,
            /* Zero is the policy engine saying there is no contract. */
            request = caps.selected?.takeIf { it > 0 }?.let { Request(it) },
            /*
             * Whether the contract is against a programmable supply, settled
             * by the objects themselves: the position in force names one of
             * them, and an augmented supply is the programmable kind.
             *
             * Worth doing here rather than leaving to the charging stack. That
             * answers the same question from pd_type, but its attributes live
             * on the battery supply, which a platform app is never allowed to
             * read - see the sepolicy section of README.md. This reads out of
             * the one node a ROM build can be given.
             */
            programmable = selected(caps)?.let { it is SourceCapability.Programmable },
            /*
             * Left to the charging stack where there is no contract: a cable
             * that negotiated nothing is a charger type rather than a
             * protocol, and BC1.2 detection is what knows about those.
             */
            protocol = protocol(caps, values["$directory/pe_ready"]),
        )
    }

    /** The capability the position in force names, where both are known. */
    private fun selected(caps: Caps): SourceCapability? =
        caps.selected?.let { position ->
            caps.remoteSource.firstOrNull { it.position == position }
        }

    private fun protocol(caps: Caps, peReady: String?): String? {
        if (peReady != "yes" || caps.remoteSource.isEmpty()) {
            return null
        }
        return if (selected(caps) is SourceCapability.Programmable) PPS else PD
    }

    /**
     * The type-C class's name for a port the controller calls
     * "type_c_port<n>". Null where the name carries no number, since nothing
     * better can be guessed and guessing wrong on a two-port board would
     * describe the other cable.
     */
    private fun typecName(tcpc: String): String? =
        tcpc.takeLastWhile { it.isDigit() }.takeIf { it.isNotEmpty() }?.let { "port$it" }

    private const val EXPLICIT = "explicit"
    private const val IMPLICIT = "implicit"
    private const val PD = "PD"
    private const val PPS = "PPS"
}

/**
 * The four object lists and the position in force, as `caps_info` prints them.
 *
 * Both ends are there: what this port advertises as a source and as a sink,
 * and what the other end advertised as each. The charger's source list is what
 * somebody opened this for; the port's own sink list is worth showing because
 * it is what the phone asked to be offered.
 */
internal data class Caps(
    val selected: Int? = null,
    val localSource: List<SourceCapability> = emptyList(),
    val localSink: List<SourceCapability> = emptyList(),
    val remoteSource: List<SourceCapability> = emptyList(),
    val remoteSink: List<SourceCapability> = emptyList(),
)

internal object CapsInfo {
    /*
     * A heading per list and one line per object under it:
     *
     *   selected_cap = 6
     *   local_src_cap(type, vmin, vmax, oper)
     *   local_snk_cap(type, vmin, vmax, ioper)
     *   0 5000 5000 3000
     *   3 3600 10000 5000
     *   remote_src_cap(type, vmin, vmax, ioper)
     *   remote_snk_cap(type, vmin, vmax, ioper)
     *
     * Read by heading rather than by position: an empty list prints its
     * heading and nothing else, which is what an unplugged port looks like,
     * and the order they come in is the kernel's business rather than this.
     */
    fun parse(text: String): Caps {
        var caps = Caps()
        var section: String? = null
        var position = 0

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                continue
            }

            if (line.startsWith(SELECTED)) {
                caps = caps.copy(selected = line.substringAfter('=').trim().toIntOrNull())
                continue
            }

            val heading = line.substringBefore('(')
            if (line.contains('(') && heading in LISTS) {
                section = heading
                position = 0
                continue
            }

            val capability = capability(line, position + 1) ?: continue
            position++

            caps = when (section) {
                LOCAL_SOURCE -> caps.copy(localSource = caps.localSource + capability)
                LOCAL_SINK -> caps.copy(localSink = caps.localSink + capability)
                REMOTE_SOURCE -> caps.copy(remoteSource = caps.remoteSource + capability)
                REMOTE_SINK -> caps.copy(remoteSink = caps.remoteSink + capability)
                else -> caps
            }
        }

        return caps
    }

    /** "type vmin vmax amount", where the amount is what the type calls for. */
    private fun capability(line: String, position: Int): SourceCapability? {
        val fields = line.split(' ').filter { it.isNotEmpty() }.map { it.toIntOrNull() }
        if (fields.size != FIELDS || fields.any { it == null }) {
            return null
        }

        return PowerDeliveryObject.decoded(
            type = fields[0]!!,
            minMillivolts = fields[1]!!,
            maxMillivolts = fields[2]!!,
            amount = fields[3]!!,
            position = position,
        ).takeIf { PowerDeliveryObject.isPlausible(it) }
    }

    private const val SELECTED = "selected_cap"
    private const val LOCAL_SOURCE = "local_src_cap"
    private const val LOCAL_SINK = "local_snk_cap"
    private const val REMOTE_SOURCE = "remote_src_cap"
    private const val REMOTE_SINK = "remote_snk_cap"
    private val LISTS = setOf(LOCAL_SOURCE, LOCAL_SINK, REMOTE_SOURCE, REMOTE_SINK)

    private const val FIELDS = 4
}
