/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.mtk.pd_info

import android.content.Context
import org.witaqua.mtk.pd_info.model.Adapter
import org.witaqua.mtk.pd_info.model.EmptyReason
import org.witaqua.mtk.pd_info.model.Measured
import org.witaqua.mtk.pd_info.model.PdConnection
import org.witaqua.mtk.pd_info.model.Port
import org.witaqua.mtk.pd_info.model.QuickCharge
import org.witaqua.mtk.pd_info.model.Request
import org.witaqua.mtk.pd_info.model.Snapshot
import org.witaqua.mtk.pd_info.model.SourceCapability
import org.witaqua.mtk.pd_info.source.principal
import java.text.NumberFormat

/** A heading and the lines under it, which is all either screen needs. */
data class Section(val title: String, val rows: List<Row>, val note: String? = null)

data class Row(val label: String, val value: String)

/**
 * Turning a snapshot into the lines somebody reads. Kept away from both the
 * decoding and the user interface: what the interface says, how it is worded,
 * and where it is drawn are three separate arguments.
 */
class Renderer(private val context: Context) {
    fun sections(snapshot: Snapshot?): List<Section> {
        if (snapshot == null || !snapshot.hasAnything) {
            return listOf(unavailable())
        }

        /*
         * With nothing plugged in there is one fact worth showing and a page
         * of empty rows not worth showing. The roles a port would take and the
         * protocol it is not using say nothing, and putting the object list's
         * absence down to the platform would be wrong - the cable is out.
         */
        if (snapshot.emptyReason == EmptyReason.NOTHING_ATTACHED) {
            /*
             * What each port asks for is the port's own, so it is worth saying
             * with nothing plugged in - it is the one thing that tells two
             * sockets apart before either is used.
             */
            return listOf(detached(snapshot)) + snapshot.ports.mapNotNull { sinkSection(it) }
        }

        return buildList {
            snapshot.ports.forEach { port ->
                add(portSection(port))
                if (port.capabilities.isNotEmpty()) {
                    add(capabilitySection(port))
                }
                contractSection(port)?.let { add(it) }
                sinkSection(port)?.let { add(it) }
            }

            snapshot.adapter?.let { adapterSection(it)?.let(::add) }
            snapshot.measured?.let { add(measuredSection(it)) }
            emptyNote(snapshot)?.let { add(it) }
        }
    }

    /**
     * One line for the band at the top: what is on the other end of the cable,
     * and at what. It is the answer somebody opened this for, and the table
     * below is the working.
     */
    fun headline(snapshot: Snapshot?): String {
        if (snapshot == null) {
            return context.getString(R.string.headline_nothing)
        }

        val port = snapshot.ports.principal()
            ?: return context.getString(R.string.headline_nothing)

        if (snapshot.emptyReason == EmptyReason.NOTHING_ATTACHED) {
            return context.getString(R.string.headline_nothing)
        }

        /*
         * What is arriving. This stack publishes no agreed figures anywhere -
         * the request object is reduced to its position on the way out of the
         * kernel - so the measurement is the only voltage and current there
         * are. The rows below say which it is; a headline that hedged would
         * not be one.
         */
        val arriving = snapshot.measured?.let { measured ->
            val millivolts = measured.millivolts ?: return@let null
            context.getString(
                R.string.headline_at,
                volts(millivolts),
                amps(measured.milliamps ?: 0),
            )
        }

        val protocol = port.protocol ?: port.contract?.let { word(it) }

        return listOfNotNull(protocol, arriving)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(context.getString(R.string.list_separator))
            ?: context.getString(R.string.headline_nothing)
    }

    private fun portSection(port: Port): Section {
        /*
         * A port with nothing on it has no roles worth printing. The class
         * answers for it anyway, which on a board with two ports would have
         * the empty one describing a contract it is not in, beside the one
         * that is.
         */
        if (!port.attached) {
            return detachedPort(port)
        }

        return Section(
            title = portTitle(port),
            rows = buildList {
                port.contract?.let {
                    add(Row(context.getString(R.string.label_contract), word(it)))
                }
                port.powerRole?.let {
                    add(Row(context.getString(R.string.label_power_role), word(it)))
                }
                port.dataRole?.let {
                    add(Row(context.getString(R.string.label_data_role), word(it)))
                }
                port.protocol?.let { add(Row(context.getString(R.string.label_protocol), it)) }
                port.pdRevision?.let {
                    add(Row(context.getString(R.string.label_pd_revision), it))
                }
                port.partnerSupportsPd?.let {
                    add(
                        Row(
                            context.getString(R.string.label_partner),
                            context.getString(
                                if (it) R.string.partner_supports_pd else R.string.partner_no_pd
                            ),
                        )
                    )
                }
            },
        )
    }

    private fun portTitle(port: Port) = port.name
        ?.let { context.getString(R.string.section_port, it) }
        ?: context.getString(R.string.section_port_unnamed)

    /**
     * An empty port beside a busy one. The role definition stays because it is
     * the port's own rather than a contract's; the note does not, since the
     * screen has something else on it to read - where every port is empty the
     * page below says it once instead.
     */
    private fun detachedPort(port: Port) = Section(
        title = portTitle(port),
        rows = buildList {
            add(
                Row(
                    context.getString(R.string.label_state),
                    context.getString(R.string.state_detached),
                )
            )
            port.roleDefinition?.let {
                add(Row(context.getString(R.string.label_role_definition), it))
            }
            port.pdRevision?.let { add(Row(context.getString(R.string.label_pd_revision), it)) }
        },
    )

    private fun capabilitySection(port: Port) = Section(
        title = context.getString(R.string.section_advertised),
        rows = port.capabilities.map { capability ->
            Row(
                context.getString(R.string.label_object, capability.position),
                capability(capability),
            )
        },
        /*
         * The bits a source uses to describe itself - mains powered, dual-role,
         * USB capable - do not survive this interface: the port controller
         * decodes each object into four figures and the flags are not among
         * them. Said once here rather than left as a silence.
         */
        note = context.getString(R.string.note_no_source_flags),
    )

    /**
     * What this port advertises to a charger. Named after the port, since the
     * point of showing it at all is that a board with two need not have two of
     * the same.
     */
    private fun sinkSection(port: Port): Section? {
        if (port.sinkCapabilities.isEmpty()) {
            return null
        }

        return Section(
            title = port.name
                ?.let { context.getString(R.string.section_requested_port, it) }
                ?: context.getString(R.string.section_requested),
            rows = port.sinkCapabilities.map { capability ->
                Row(
                    context.getString(R.string.label_object, capability.position),
                    capability(capability),
                )
            },
        )
    }

    /*
     * Which line of the menu was ordered. The position is all this stack says:
     * selected_cap is RDO_POS() of the last request, and the amounts asked
     * against it are not published anywhere - so the object is named, what it
     * offers is repeated from the list above, and the figures are left to the
     * measurement.
     */
    private fun contractSection(port: Port): Section? {
        val request = port.request ?: return null
        val against = port.capabilities.firstOrNull { it.position == request.objectPosition }

        return Section(
            title = context.getString(R.string.section_in_use),
            rows = listOf(Row(context.getString(R.string.label_request), request(request, against))),
            note = when {
                port.programmable == true -> context.getString(R.string.note_request_programmable)
                else -> context.getString(R.string.note_request_position)
            },
        )
    }

    /**
     * What the charging stack made of the adapter. None of it is on the wire -
     * it is the charger driver's reading of the negotiation - so it is its own
     * section rather than more rows about the port.
     */
    private fun adapterSection(adapter: Adapter): Section? {
        val rows = buildList {
            adapter.realType?.let {
                add(Row(context.getString(R.string.label_reported_as), it))
            }
            adapter.connection?.let {
                add(Row(context.getString(R.string.label_handshake), connection(it)))
            }
            adapter.quickCharge?.let {
                add(Row(context.getString(R.string.label_quick_charge), quickCharge(it)))
            }
            adapter.apdoMaxWatts?.let {
                add(
                    Row(
                        context.getString(R.string.label_apdo_max),
                        context.getString(R.string.value_watts, it.toString()),
                    )
                )
            }
            adapter.powerMaxWatts?.let {
                add(
                    Row(
                        context.getString(R.string.label_power_max),
                        context.getString(R.string.value_watts, it.toString()),
                    )
                )
            }
            adapter.authenticated?.let {
                add(
                    Row(
                        context.getString(R.string.label_authenticated),
                        context.getString(
                            if (it) R.string.authenticated_yes else R.string.authenticated_no
                        ),
                    )
                )
            }
        }

        if (rows.isEmpty()) {
            return null
        }

        return Section(
            title = context.getString(R.string.section_adapter),
            rows = rows,
            note = context.getString(R.string.note_adapter),
        )
    }

    private fun measuredSection(measured: Measured) = Section(
        title = context.getString(R.string.section_measured, measured.supply),
        rows = buildList {
            measured.millivolts?.let {
                add(
                    Row(
                        context.getString(R.string.label_voltage),
                        context.getString(R.string.value_volts, volts(it)),
                    )
                )
            }
            measured.milliamps?.let {
                add(
                    Row(
                        context.getString(R.string.label_current),
                        context.getString(R.string.value_amps, amps(it)),
                    )
                )
            }
        },
        note = context.getString(R.string.note_measured_not_agreed),
    )

    /**
     * Nothing on the other end of anything. A row per port, one saying what
     * they would do when something arrives, and a line to explain the emptiness
     * so that it does not read as a failure to find anything.
     */
    private fun detached(snapshot: Snapshot) = Section(
        title = context.getString(R.string.section_state),
        rows = buildList {
            if (snapshot.ports.size > 1) {
                snapshot.ports.forEach { port ->
                    add(Row(portTitle(port), context.getString(R.string.state_detached)))
                }
            } else {
                snapshot.ports.firstOrNull()?.name?.let {
                    add(Row(context.getString(R.string.label_port), it))
                }
                add(
                    Row(
                        context.getString(R.string.label_state),
                        context.getString(R.string.state_detached),
                    )
                )
            }

            /*
             * Properties of the port rather than of a contract, so they stay -
             * once, where the ports agree, and not at all where they do not
             * rather than saying it twice about nothing.
             */
            agreed(snapshot) { it.roleDefinition }?.let {
                add(Row(context.getString(R.string.label_role_definition), it))
            }
            agreed(snapshot) { it.pdRevision }?.let {
                add(Row(context.getString(R.string.label_pd_revision), it))
            }
        },
        note = context.getString(R.string.note_detached),
    )

    private fun agreed(snapshot: Snapshot, of: (Port) -> String?): String? =
        snapshot.ports.map(of).distinct().singleOrNull()

    private fun emptyNote(snapshot: Snapshot): Section? =
        when (snapshot.emptyReason) {
            EmptyReason.NO_CONTRACT -> Section(
                title = context.getString(R.string.section_why_empty),
                rows = emptyList(),
                note = context.getString(R.string.note_no_contract),
            )

            EmptyReason.NO_OBJECT_INTERFACE -> Section(
                title = context.getString(R.string.section_why_empty),
                rows = emptyList(),
                note = context.getString(R.string.note_no_object_interface),
            )

            /* Handled before any of this, by returning a page of its own. */
            EmptyReason.NOTHING_ATTACHED, null -> null
        }

    private fun unavailable() = Section(
        title = context.getString(R.string.section_unavailable),
        rows = listOf(
            Row(context.getString(R.string.label_platform), Platform.platform.ifEmpty { "?" }),
            Row(context.getString(R.string.label_kernel), Platform.kernelRelease),
        ),
        note = context.getString(
            if (Platform.looksMediatek) {
                R.string.note_unavailable
            } else {
                R.string.note_unavailable_not_mediatek
            }
        ),
    )

    fun capability(capability: SourceCapability): String =
        when (capability) {
            is SourceCapability.Fixed -> context.getString(
                R.string.capability_fixed,
                volts(capability.millivolts),
                amps(capability.maxMilliamps),
            )

            is SourceCapability.Battery -> context.getString(
                R.string.capability_battery,
                volts(capability.minMillivolts),
                volts(capability.maxMillivolts),
                watts(capability.maxMilliwatts),
            )

            is SourceCapability.Variable -> context.getString(
                R.string.capability_variable,
                volts(capability.minMillivolts),
                volts(capability.maxMillivolts),
                amps(capability.maxMilliamps),
            )

            is SourceCapability.Programmable -> context.getString(
                R.string.capability_programmable,
                volts(capability.minMillivolts),
                volts(capability.maxMillivolts),
                amps(capability.maxMilliamps),
            )

            /*
             * A supply this kernel has no decoder for - an adjustable voltage
             * supply, or extended range. The type byte is what it said, and is
             * more use than dropping the row.
             */
            is SourceCapability.Unrecognised -> context.getString(
                R.string.capability_unrecognised,
                capability.type,
            )
        }

    private fun request(request: Request, against: SourceCapability?): String =
        against?.let {
            context.getString(
                R.string.request_object_offering,
                request.objectPosition,
                capability(it),
            )
        } ?: context.getString(R.string.request_object, request.objectPosition)

    private fun connection(connection: PdConnection): String =
        context.getString(
            when (connection) {
                PdConnection.NONE -> R.string.connection_none
                PdConnection.HARD_RESET -> R.string.connection_hard_reset
                PdConnection.SOFT_RESET -> R.string.connection_soft_reset
                PdConnection.READY -> R.string.connection_ready
                PdConnection.READY_PD30 -> R.string.connection_ready_pd30
                PdConnection.READY_APDO -> R.string.connection_ready_apdo
                PdConnection.TYPEC_ONLY -> R.string.connection_typec_only
                PdConnection.NEW_SOURCE_CAPABILITIES -> R.string.connection_new_source
            }
        )

    private fun quickCharge(quickCharge: QuickCharge): String =
        context.getString(
            when (quickCharge) {
                QuickCharge.NORMAL -> R.string.quick_charge_normal
                QuickCharge.FAST -> R.string.quick_charge_fast
                QuickCharge.FLASH -> R.string.quick_charge_flash
                QuickCharge.TURBO -> R.string.quick_charge_turbo
                QuickCharge.SUPER -> R.string.quick_charge_super
            }
        )

    /*
     * The driver's own words, expanded. They are exact and say little to
     * anybody who has not read the specification; anything unrecognised is
     * passed through rather than dropped.
     */
    private fun word(raw: String): String =
        when (raw) {
            "source" -> context.getString(R.string.role_source)
            "sink" -> context.getString(R.string.role_sink)
            "dfp", "host" -> context.getString(R.string.role_host)
            "ufp", "device" -> context.getString(R.string.role_device)
            "none" -> context.getString(R.string.role_none)
            "explicit", "usb_power_delivery" -> context.getString(R.string.contract_explicit)
            "implicit" -> context.getString(R.string.contract_implicit)
            /* power_operation_mode, where the type-C class is all there is. */
            "default" -> context.getString(R.string.contract_typec_default)
            "1.5A", "3.0A" -> context.getString(R.string.contract_typec_current, raw)
            else -> raw
        }

    /*
     * Volts lose a trailing ".0" - 9V, not 9.0V - while amps keep a digit,
     * because a supply rated at exactly three amperes is still quoted as 3.0A.
     * Both stop at two, which is finer than the port controller prints.
     */
    private fun volts(millivolts: Int) = scaled(millivolts, 0)

    private fun amps(milliamps: Int) = scaled(milliamps, 1)

    private fun watts(milliwatts: Int) = scaled(milliwatts, 0)

    private fun scaled(milli: Int, minimumDigits: Int): String =
        NumberFormat.getInstance().apply {
            minimumFractionDigits = minimumDigits
            maximumFractionDigits = 2
        }.format(milli / 1000.0)
}
