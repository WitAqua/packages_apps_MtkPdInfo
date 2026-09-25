/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.mtk.pd_info.model

/**
 * Everything a source managed to find, in terms that do not say which kernel
 * interface it came out of. Each field is nullable because the interfaces
 * carry different halves of this and none carries all of it.
 */
data class Snapshot(
    /** Which reader produced this, for the screen to say so. */
    val origin: Origin,

    val ports: List<Port>,

    /** What the charger firmware says about the adapter, where it says it. */
    val adapter: Adapter? = null,

    /** What the charger-facing power supply measures, where there is one. */
    val measured: Measured? = null,

    /**
     * Set when the interface exists but the kernel put nothing in it. Worth
     * separating from "no interface": the first is a state of the port that no
     * amount of privilege gets around, the second means look elsewhere.
     */
    val emptyReason: EmptyReason? = null,
) {
    val hasAnything: Boolean
        get() = ports.isNotEmpty() || measured != null || adapter != null
}

enum class Origin {
    /**
     * MediaTek's own port controller: the decoded capabilities of both ends
     * and the object position in force.
     */
    TCPC,

    /**
     * The type-C class and the charger's power supply, which between them
     * describe the contract and carry no object list at all.
     */
    CHARGER,
}

enum class EmptyReason {
    /**
     * Nothing is plugged in, so there is nothing to have read. Kept apart from
     * the reason below because they look identical from the interface - no
     * capabilities either way - and saying the wrong one is worse than saying
     * nothing: one is a cable, the other is a platform.
     */
    NOTHING_ATTACHED,

    /**
     * Something is attached and the port controller is there, but it holds no
     * capabilities for the other end. That is what a type-C only cable looks
     * like: power delivery was never negotiated, so no source capabilities
     * message ever arrived.
     */
    NO_CONTRACT,

    /**
     * Nothing on this kernel publishes an object list: no MediaTek port
     * controller class, and the upstream power delivery class holds no devices
     * because MediaTek's stack does not register any. The charger still says
     * what it settled on, and that is all there is.
     */
    NO_OBJECT_INTERFACE,
}

data class Port(
    /**
     * Whether anything is on the other end. The type-C class drops the partner
     * device when the cable comes out, which is the plainest signal there is.
     */
    val attached: Boolean = true,

    /**
     * The type-C port this belongs to, or null when the class that names it
     * could not be read - which happens, and is no reason to drop the rest.
     */
    val name: String?,

    /** "sink", "source", "none" - already reduced from any bracketed list. */
    val powerRole: String? = null,
    val dataRole: String? = null,

    /** "explicit"/"implicit": whether a contract was reached. */
    val contract: String? = null,

    val pdRevision: String? = null,
    val partnerSupportsPd: Boolean? = null,

    /**
     * How this port is set up to behave before anything is plugged in -
     * MediaTek's own role definition, "DRP" or "Try.SNK" rather than the role
     * currently held.
     */
    val roleDefinition: String? = null,

    /** What the other end advertised. Empty when it was never read. */
    val capabilities: List<SourceCapability> = emptyList(),

    /**
     * What this port asks for, as it advertises itself to a charger. The same
     * object shapes as above, because a sink's objects are the same data
     * objects read the other way round: where a source says what it can
     * deliver, a sink says what it would operate at.
     */
    val sinkCapabilities: List<SourceCapability> = emptyList(),

    /** What this end asked for, to the extent the interface exposes it. */
    val request: Request? = null,

    /** "PD", "PPS", "Type-C" - the protocol actually in force. */
    val protocol: String? = null,

    /**
     * Whether the contract is against a programmable supply. Null where
     * nothing on the board publishes it, which is why this is not a plain
     * boolean: it separates "a fixed contract" from "nobody said".
     */
    val programmable: Boolean? = null,
)

/**
 * What the charger firmware made of the adapter. None of this is on the wire:
 * it is the charging stack's own reading of the negotiation, and on a Xiaomi
 * board several of these drive the charging animation rather than anything
 * electrical.
 */
data class Adapter(
    /** The charger's own name for the adapter: "USB_PD", "SDP", "DCP". */
    val realType: String? = null,

    /** How far the power delivery handshake got, in MediaTek's terms. */
    val connection: PdConnection? = null,

    /** Xiaomi's classification of the charge rate, where the board has one. */
    val quickCharge: QuickCharge? = null,

    /**
     * The same idea in words rather than in an enum. MediaTek's own charger
     * framework has no quick_charge_type; boards built on it carry a
     * charge_rate that is already a string - "Normal", "Turbo" - so it is kept
     * as one rather than mapped onto Xiaomi's list, which is not the same set.
     */
    val rate: String? = null,

    /** The best programmable supply on offer, in watts. */
    val apdoMaxWatts: Int? = null,

    /** What the charging stack settled on as the budget, in watts. */
    val powerMaxWatts: Int? = null,

    /** Whether the adapter passed Xiaomi's authentication, where it is run. */
    val authenticated: Boolean? = null,
)

/**
 * `enum mtk_pd_connect_type` out of drivers/power/supply/adapter_class.h, as
 * /sys/class/power_supply/usb/pd_type reports it. It is the only thing on a
 * MediaTek board that distinguishes a programmable contract from a fixed one
 * without reading the objects.
 */
enum class PdConnection {
    NONE,
    HARD_RESET,
    SOFT_RESET,
    /** Power delivery 2.0: fixed supplies only. */
    READY,
    /** Power delivery 3.0, still on a fixed supply. */
    READY_PD30,
    /** A programmable supply: the sink picks the voltage. */
    READY_APDO,
    /** Type-C current advertisement and no power delivery at all. */
    TYPEC_ONLY,
    NEW_SOURCE_CAPABILITIES,
    ;

    val negotiated: Boolean
        get() = this == READY || this == READY_PD30 || this == READY_APDO ||
            this == NEW_SOURCE_CAPABILITIES

    val programmable: Boolean
        get() = this == READY_APDO

    companion object {
        fun of(value: Int?): PdConnection? = values().getOrNull(value ?: -1)
    }
}

/** `enum quick_charge_type`, Xiaomi's own badge for the charge rate. */
enum class QuickCharge {
    NORMAL,
    FAST,
    FLASH,
    TURBO,
    SUPER,
    ;

    companion object {
        fun of(value: Int?): QuickCharge? = values().getOrNull(value ?: -1)
    }
}

data class Measured(
    val supply: String,
    val type: String? = null,
    val millivolts: Int? = null,
    val milliamps: Int? = null,
)
