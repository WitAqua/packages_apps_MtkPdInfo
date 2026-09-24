/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.mtk.pd_info.model

/*
 * A USB Power Delivery data object, as MediaTek's port controller hands it
 * over. The specification puts one in 32 bits and the same word means the same
 * thing on every port that speaks power delivery, but this stack never lets a
 * word out: tcpci_core.c runs each one through tcpm_extract_power_cap_val()
 * and prints the four fields that come back.
 *
 *   /sys/class/tcpc/<port>/caps_info
 *
 *     selected_cap = 6
 *     local_src_cap(type, vmin, vmax, oper)
 *     local_snk_cap(type, vmin, vmax, ioper)
 *     0 5000 5000 3000
 *     3 3600 10000 5000
 *     remote_src_cap(type, vmin, vmax, ioper)
 *     remote_snk_cap(type, vmin, vmax, ioper)
 *
 * What that costs is in docs/kernel.md: the source-wide flag bits are dropped
 * on the way through, and an object this kernel cannot decode arrives as a
 * type of 255 with its figures zeroed rather than as the word it was.
 */

/** One entry of a source's advertised capabilities. */
sealed interface SourceCapability {
    /** Where in the advertisement this one sat. Requests index by it. */
    val position: Int

    /** A single voltage, and the most that may be drawn at it. */
    data class Fixed(
        override val position: Int,
        val millivolts: Int,
        val maxMilliamps: Int,
    ) : SourceCapability

    /** A power budget across a voltage range, for a battery source. */
    data class Battery(
        override val position: Int,
        val minMillivolts: Int,
        val maxMillivolts: Int,
        val maxMilliwatts: Int,
    ) : SourceCapability

    /** A current limit across a voltage range the source picks. */
    data class Variable(
        override val position: Int,
        val minMillivolts: Int,
        val maxMillivolts: Int,
        val maxMilliamps: Int,
    ) : SourceCapability

    /** Programmable power supply: the sink chooses the voltage, in steps. */
    data class Programmable(
        override val position: Int,
        val minMillivolts: Int,
        val maxMillivolts: Int,
        val maxMilliamps: Int,
    ) : SourceCapability

    /**
     * An object the port controller would not decode. It is not a parse
     * failure here: dpm_extract_pdo_info() knows fixed, battery, variable and
     * the programmable augmented supply, and answers 255 with the figures
     * zeroed for anything else - an adjustable voltage supply, or extended
     * range, both of which are newer than this stack. The row stays because
     * the charger did offer something.
     */
    data class Unrecognised(override val position: Int, val type: Int) : SourceCapability
}

/**
 * What the sink is drawing on, as far as this interface says.
 *
 * MediaTek publishes the object position and nothing else: `selected_cap` is
 * RDO_POS() of the last request, so the object is known and the figures asked
 * against it are not. The voltage and current that were agreed are not
 * anywhere in sysfs on this stack - what is measured at the charger is the
 * nearest thing to them, and the screen says which it is showing.
 */
data class Request(val objectPosition: Int)

object PowerDeliveryObject {
    /* enum tcpm_power_cap_val_type, drivers/misc/mediatek/typec/tcpc/inc/tcpm.h */
    private const val TYPE_FIXED = 0
    private const val TYPE_BATTERY = 1
    private const val TYPE_VARIABLE = 2
    private const val TYPE_AUGMENTED = 3

    /**
     * One capability out of the four figures the port controller prints for
     * it. [amount] is milliamps for every type but the battery supply, where
     * the union in struct tcpm_power_cap_val holds microwatts instead - see
     * tcpm_extract_power_cap_val().
     */
    fun decoded(
        type: Int,
        minMillivolts: Int,
        maxMillivolts: Int,
        amount: Int,
        position: Int,
    ): SourceCapability =
        when (type) {
            TYPE_FIXED -> SourceCapability.Fixed(
                position = position,
                /* One voltage, printed twice as a range of no width. */
                millivolts = maxMillivolts,
                maxMilliamps = amount,
            )

            TYPE_BATTERY -> SourceCapability.Battery(
                position = position,
                minMillivolts = minMillivolts,
                maxMillivolts = maxMillivolts,
                maxMilliwatts = amount / 1000,
            )

            TYPE_VARIABLE -> SourceCapability.Variable(
                position = position,
                minMillivolts = minMillivolts,
                maxMillivolts = maxMillivolts,
                maxMilliamps = amount,
            )

            TYPE_AUGMENTED -> SourceCapability.Programmable(
                position = position,
                minMillivolts = minMillivolts,
                maxMillivolts = maxMillivolts,
                maxMilliamps = amount,
            )

            else -> SourceCapability.Unrecognised(position, type)
        }

    /**
     * Whether this describes a supply that could exist. Every type has to name
     * a voltage it can deliver and something it can deliver at it, and a range
     * has to run the right way round. An object the kernel could not decode is
     * kept whatever its figures, since the zeros are the kernel's rather than
     * the charger's.
     */
    fun isPlausible(capability: SourceCapability): Boolean =
        when (capability) {
            is SourceCapability.Fixed ->
                capability.millivolts > 0 && capability.maxMilliamps > 0

            is SourceCapability.Battery ->
                capability.minMillivolts > 0 &&
                    capability.maxMillivolts >= capability.minMillivolts &&
                    capability.maxMilliwatts > 0

            is SourceCapability.Variable ->
                capability.minMillivolts > 0 &&
                    capability.maxMillivolts >= capability.minMillivolts &&
                    capability.maxMilliamps > 0

            is SourceCapability.Programmable ->
                capability.minMillivolts > 0 &&
                    capability.maxMillivolts >= capability.minMillivolts &&
                    capability.maxMilliamps > 0

            is SourceCapability.Unrecognised -> true
        }
}
