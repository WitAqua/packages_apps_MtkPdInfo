# What the kernel has to publish

This app does not talk to the port. It reads what the kernel already knows, so
what it can show depends entirely on what MediaTek's stack exposes - and unlike
the qualcomm side of this, there is no version question. The interface below
has been the same since the 4.x kernels and is still the same on android16-6.12.

## The one interface that carries objects

`drivers/misc/mediatek/typec/tcpc`, which on these boards is the whole power
delivery stack: the type-C state machine, the policy engine and the device
policy manager. `tcpci_core.c` registers a class of its own:

```
/sys/class/tcpc/<port>/caps_info     both ends' objects, and the position in force
/sys/class/tcpc/<port>/pe_ready      "yes" once a contract is reached
/sys/class/tcpc/<port>/typec_role    how the port is set up to behave
/sys/class/tcpc/<port>/local_rp_level
/sys/class/tcpc/<port>/vbus_level
/sys/class/tcpc/<port>/cc_high
/sys/class/tcpc/<port>/pd_test       write-only, and not something to write to
```

The port is named after the device tree node - `type_c_port0` on every Xiaomi
board checked. The older trees spell two of these differently: `role_def` for
`typec_role` and `rp_lvl` for `local_rp_level`, with an extra `info` that
prints a paragraph. Both spellings are read.

`caps_info` is the one worth having. Four lists and the position in force:

```
selected_cap = 0
local_src_cap(type, vmin, vmax, oper)
local_snk_cap(type, vmin, vmax, ioper)
0 5000 5000 3000
3 3600 10000 5000
remote_src_cap(type, vmin, vmax, ioper)
remote_snk_cap(type, vmin, vmax, ioper)
```

That is a real reading, from a handset with a data cable in it: the phone will
take 5V at 3A or anything from 3.6V to 10V at 5A from a programmable supply,
nothing was offered because nothing on the other end speaks power delivery, and
`selected_cap = 0` is the policy engine saying there is no contract.

The type is `enum tcpm_power_cap_val_type`: 0 fixed, 1 battery, 2 variable, 3
augmented - which in practice means programmable, see below - and 255 for one
it would not decode. `selected_cap` is `RDO_POS(pd_port->last_rdo)`, so it is
the object position the last request named, counting from one.

## What the interface costs

`tcpci_core.c` prints `cap.type`, `cap.min_mv`, `cap.max_mv` and `cap.ma` for
each object, which `tcpm_extract_power_cap_val()` fills in from the 32-bit word.
Three things do not survive that:

**The source flags.** Whether the charger is mains powered, dual-role capable
or USB communications capable lives in the top bits of the first fixed object.
`dpm_extract_pdo_info()` does not carry them into `struct dpm_pdo_info_t`, so
they are gone before userspace sees anything. Nothing in sysfs has them.

**The request's own figures.** Only its object position is published.
`pd_port->pe_data.selected_cap` is the position; the operating current, the
limit and - for a programmable supply - the voltage asked for are in
`last_rdo`, which no attribute prints. So the negotiated voltage and current
are not readable on this platform at all, and what the charger measures is the
nearest thing to them.

**Anything newer than the decoder.** `dpm_extract_apdo_info()` handles
`APDO_TYPE_PPS` and sets the type to `TCPM_POWER_CAP_VAL_TYPE_UNKNOWN` for
everything else, with the figures left at zero. An adjustable voltage supply or
an extended range object therefore arrives as `255 0 0 0`. The app shows the
row rather than dropping it, because the charger did offer something.

One field means two different things, which is worth knowing when reading the
kernel rather than this app: `struct tcpm_power_cap_val` holds a union of `ma`
and `uw`, and a battery supply fills in the microwatts. `caps_info` prints the
union either way.

## Why the upstream class is empty

`/sys/class/usb_power_delivery` exists on these boards and holds nothing.

It is not a configuration mistake. `rt_pd_manager.c` registers the port with
the type-C class - `typec_register_port()`, `typec_register_partner()`,
`typec_partner_set_pd_revision()` - and never calls
`usb_power_delivery_register()`. The class directory comes from
`drivers/usb/typec/class.c` being built at all; with no devices registered in
it there is nothing underneath. Checked on the handset: the directory lists
empty while `caps_info` answers in full.

So the type-C class is worth reading for the roles and the revision, and the
objects come from MediaTek's own class or not at all:

```
/sys/class/typec/port0/power_role                       "source [sink]"
/sys/class/typec/port0/data_role                        "host [device]"
/sys/class/typec/port0/power_operation_mode             "default", "usb_power_delivery"
/sys/class/typec/port0/usb_power_delivery_revision      "3.1"
/sys/class/typec/port0-partner/supports_usb_power_delivery
```

## What the charging stack adds

`drivers/power/supply/mtk_charger.c` hangs a group of its own off the USB power
supply, through `usb_sysfs_create_group()`:

```
/sys/class/power_supply/usb/real_type          "USB_PD", "SDP", "DCP", ...
/sys/class/power_supply/usb/pd_type            enum mtk_pd_connect_type
/sys/class/power_supply/usb/quick_charge_type  enum quick_charge_type
/sys/class/power_supply/usb/apdo_max           watts
/sys/class/power_supply/usb/power_max          watts
/sys/class/power_supply/usb/pd_authentication  the vendor's adapter check
```

`pd_type` is the interesting one. `enum mtk_pd_connect_type` in
`drivers/power/supply/adapter_class.h` runs NONE, HARD_RESET, SOFT_RESET,
PE_READY_SNK, PE_READY_SNK_PD30, **PE_READY_SNK_APDO**, TYPEC_ONLY_SNK,
NEW_SRC_CAP - so it says both whether power delivery was reached at all and
whether the contract is against a programmable supply. That is the question the
objects cannot answer on a qualcomm board, and here it has two answers: this
one, and the type of the object `selected_cap` names. The app prefers the
second, for the policy reason in the next section.

`real_type` and `typec_mode` come out as strings and everything else in the
group as integers - `usb_sysfs_show()` special-cases those two.

Two of these are Xiaomi's rather than MediaTek's: `quick_charge_type` is the
badge behind the charging animation, and `apdo_max` is what it keys off (50W or
better is "super", 30W is "turbo"). A MediaTek board from anyone else has
`pd_type` and generally not the rest, which is why the app shows each row only
where it reads.

## Units

The power supply class documents its figures as micro units and MediaTek's
charger does not follow it:

```
/sys/class/power_supply/usb/voltage_now             5081      millivolts
/sys/class/power_supply/mtk-master-charger/voltage_now  5068  millivolts
/sys/class/power_supply/battery/voltage_now      4434000      microvolts
```

All three read at the same moment on one handset. Nothing distinguishes them
but size, so the app treats anything above 100,000 as micro units - a USB bus
does not reach 100V on any revision of the specification.

## Reading it from an app

Everything above is `0444` and none of it is readable by an ordinary app: the
labels decide, and on a stock policy they are wrong for this in two different
ways.

```
u:object_r:sysfs:s0             /sys/class/tcpc/type_c_port0/caps_info
u:object_r:sysfs:s0             /sys/class/typec/port0/
u:object_r:sysfs_batteryinfo:s0 /sys/class/power_supply/usb/real_type
```

The first two are the generic `sysfs` type, which a platform app cannot read
and which a device tree can relabel - that is ordinary device work, and
README.md has the rules.

The third cannot be granted at all. `system/sepolicy/private/domain.te` carries

```
# Platform must not have access to sysfs_batteryinfo, but should do it via health HAL
full_treble_only(`
  neverallow {
    coredomain
    -shell -apexd -init -ueventd -recovery -charger -incidentd
  } sysfs_batteryinfo:file { open read };
')
```

and `system_app` is coredomain. A ROM build therefore reads the port controller
and the type-C class and not the charging stack, unless the vendor policy
labels those attributes as something other than `sysfs_batteryinfo`. The
sideloaded build, reading through a root shell, is not a domain the neverallow
covers and gets all of it.

This is why the app works out a programmable contract from the objects rather
than from `pd_type`: the answer it can always reach is the better one to
depend on.

## Where this should end up

Two changes in MediaTek's stack would remove the gap, and neither is large.
Noted as the intended direction rather than as work done.

**Publish the objects as words.** `caps_info` decodes them because it was
written to be read by a person. A second attribute printing
`pd_port->pe_data.remote_src_cap.pdos[]` as the 32-bit words they arrived as
would carry the source flags, the supply types this decoder does not know, and
anything a later specification adds, without changing what is there.

**Publish the request.** `pd_port->pe_data.last_rdo` is one word and no
attribute prints it. With it, the negotiated voltage and current become
readable on the platform for the first time - today they can only be measured.

The upstream-shaped answer to both is to register the ports with
`usb_power_delivery_register()` the way `tcpm.c` does, which would put the
capability lists in `/sys/class/usb_power_delivery` where every other reader
expects them. That is a larger change, and it still would not carry the
request: `drivers/usb/typec/pd.c` has no attribute for one at any kernel
version.

## Checked on

- Xiaomi 14T (`degas`, MT6897), kernel `6.1.176-android14-11`, WitAqua 16.2 -
  every path, label and reading quoted above.
- Xiaomi 17T Pro (`warhol`, MT6993), kernel `6.12.38-android16-5` - the source
  this describes, `MiCode/MTK_kernel_device_modules` branch `bsp-warhol-w-oss`.
