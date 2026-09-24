# MediaTek PD Info

What the charger offered, and which line of it the phone took. Reads the USB
Power Delivery state MediaTek's port controller already knows and says it in
words:

```
ポート port0
  契約            確立済み
  電源ロール      受電 (Sink)
  プロトコル      PPS
  PD リビジョン   3.1

充電器が提示した電力
  オブジェクト 1  固定 5V 3.0A
  オブジェクト 2  固定 9V 3.0A
  ...
  オブジェクト 6  PPS 3.3〜21V 5.0A

使用中
  選択中          オブジェクト 6、PPS 3.3〜21V 5.0A

充電器
  報告された種別  USB_PD
  ハンドシェイク  PD 3.0、プログラマブル電源 (PPS)
  充電レート      HyperCharge
  最大 APDO       120W

usb での実測
  電圧            20.1V
  電流            5.4A
```

Read-only throughout. It reports a negotiation; it does not take part in one.

This is the MediaTek sibling of
[QcomPdInfo](https://github.com/WitAqua/packages_apps_QcomPdInfo), which it is
derived from: the screen, the reading strategy and the wording are that app's,
and everything below the model is new, because the two platforms publish power
delivery in entirely different places. Written for the Xiaomi 17T Pro
(`warhol`, MT6993) and the 15T Pro (`klimt`, MT6991), and run on the 14T
(`degas`, MT6897), whose port controller is the same mt6375. The interface it
reads is MediaTek's own rather than any one phone's, so any board with
`drivers/misc/mediatek/typec/tcpc` should work.

Two builds come out of this tree, differing only in how a file in `/sys` may be
opened:

| | `MtkPdInfo` | `MtkPdInfoRoot` |
| --- | --- | --- |
| Ships | inside a ROM | sideloaded onto someone else's |
| Signature | platform | ordinary |
| Shared user id | `android.uid.system` | none |
| Application id | `org.witaqua.mtk.pd_info` | `org.witaqua.mtk.pd_info.root` |
| Reads by | opening the files | a root shell |
| The charger's own readings | not reachable, see below | always |

The way in is not chosen at build time: it follows what can actually be read.
See `core/src/.../source/Sources.kt`.

## What it reads

| Node | Carries |
| --- | --- |
| `/sys/class/tcpc/<port>/caps_info` | both ends' objects, and the position in force |
| `/sys/class/tcpc/<port>/pe_ready` | whether a contract exists |
| `/sys/class/typec/port0` | the roles, the revision, whether a partner is there |
| `/sys/class/power_supply/usb/*` | what the charging stack made of the adapter |
| `/sys/class/power_supply/usb/voltage_now` | what is actually arriving |

`/sys/class/usb_power_delivery` is not among them. It is present on these
boards and always empty - `rt_pd_manager.c` registers the port with the type-C
class and never registers a power delivery device - so MediaTek's own class is
where the objects are. [docs/kernel.md](docs/kernel.md) has the detail, and the
three things that interface loses on the way out of the kernel.

## Building it into a ROM

### 1. Get the tree

Add it to your manifest, or to a local manifest:

```xml
<project name="packages_apps_MtkPdInfo"
         path="packages/apps/MtkPdInfo"
         remote="witaqua"
         revision="main" />
```

### 2. Build the app

`MtkPdInfo` is the ROM variant. Add it to the device makefile:

```make
PRODUCT_PACKAGES += \
    MtkPdInfo
```

It needs nothing else: `androidx.appcompat` comes from the tree's own
prebuilts, and there is no dependency on SettingsLib.

### 3. Label the nodes for it

This is the part that is actually device work. `MtkPdInfo` runs as
`system_app`, and on a stock policy that domain cannot read any of what it
wants. Checked on a handset, the labels are:

```
u:object_r:sysfs:s0             /sys/class/tcpc/type_c_port0/caps_info
u:object_r:sysfs:s0             /sys/class/typec/port0/
u:object_r:sysfs_batteryinfo:s0 /sys/class/power_supply/usb/real_type
```

The port controller is the one that matters, and it is the generic `sysfs`
type, so it needs a type of its own. The path to label is the device's own: the
class entry is a symlink into the platform device of whatever chip carries the
port controller, which differs per board.

```sh
adb shell su -c 'readlink -f /sys/class/tcpc/type_c_port0'
/sys/devices/platform/11f01000.i2c/i2c-5/5-0034/11f01000.i2c:mt6375@34:tcpc/tcpc/type_c_port0
```

Read it out of your own handset rather than copying that one, and label the
subtree in your device tree:

```
# sepolicy/vendor/file.te
type sysfs_tcpc, sysfs_type, fs_type;
```

```
# sepolicy/vendor/genfs_contexts
genfscon sysfs /devices/platform/<...>/tcpc   u:object_r:sysfs_tcpc:s0
genfscon sysfs /class/tcpc                    u:object_r:sysfs_tcpc:s0
```

```
# sepolicy/vendor/system_app.te
allow system_app sysfs_tcpc:dir r_dir_perms;
allow system_app sysfs_tcpc:file r_file_perms;
```

**Label the class directory too, not only what its entries point at.** The app
finds the port by listing `/sys/class/tcpc`, and listing a directory needs
`read` where walking through it needs only `search` - which
`system/sepolicy/private/domain.te` already grants every domain on generic
`sysfs`. So the miss produces no denial at all: `File.list()` returns null, the
source looks absent, and the screen says the interface was not found. Labelling
the device subtree alone gets exactly that.

The type-C class wants the same treatment, for the roles and the revision, but
not by the same means: MediaTek's base policy already carries
`genfscon sysfs /class/typec u:object_r:sysfs_usb_nonplat:s0`, and **a second
genfscon for one path does not build**. Ask for the type the vendor already
used rather than relabelling:

```
# sepolicy/vendor/system_app.te
allow system_app sysfs_usb_nonplat:dir r_dir_perms;
allow system_app sysfs_usb_nonplat:file r_file_perms;
```

**Relabelling takes the subtree away from whoever had it.** The type-C port's
platform device is generic `sysfs` on a stock policy, which every domain can
walk; give it a type of its own and the ones that were reading it stop. On a
MediaTek board that is the USB HAL, which writes `power_role`, `data_role` and
`port_type` there to swap roles, so give it back both:

```
# sepolicy/vendor/mtk_hal_usb.te
allow mtk_hal_usb sysfs_tcpc:dir r_dir_perms;
allow mtk_hal_usb sysfs_tcpc:file rw_file_perms;
```

`logcat | grep avc` after the first boot names anything else that was relying
on the old label.

**The charger's own readings cannot be granted.**
`system/sepolicy/private/domain.te` has a neverallow on `sysfs_batteryinfo` for
every coredomain but a handful, and `system_app` is coredomain:

```
# Platform must not have access to sysfs_batteryinfo, but should do it via health HAL
full_treble_only(`
  neverallow {
    coredomain
    -shell -apexd -init -ueventd -recovery -charger -incidentd
  } sysfs_batteryinfo:file { open read };
')
```

So a ROM build shows the objects, the contract and the position in use, and
leaves out the charger section and the measurement. Both come back on a board
whose vendor policy labels those attributes as something else, and the
sideloaded build has them either way.

Nothing in the app depends on them: which object is in use settles whether the
contract is a programmable one, which is the question `pd_type` would have
answered.

Check the labels on the handset rather than trusting any of this:

```sh
adb shell su -c 'ls -Zd /sys/class/tcpc/* /sys/class/typec/*'
adb shell su -c 'ls -Z /sys/class/power_supply/usb/real_type'
```

`logcat | grep avc` while the screen is open will name anything still refused.

## Building the sideloaded variant

Soong is the primary build - the ROM variant wants the platform signature and
the system shared user id, which only a build inside an android tree can give
it. `MtkPdInfoRoot` can also be built with Gradle, which is what CI does, since
a runner has no tree:

```sh
gradle assembleRelease
```

It comes out unsigned unless `STORE_FILE`, `STORE_PASSWORD`, `KEY_ALIAS` and
`KEY_PASSWORD` are in the environment.

For the sideloaded build, packaged as a module for KernelSU or Magisk:
[Mtk-PD-Info-Module](https://github.com/WitAqua-tools/Mtk-PD-Info-Module).

`gradle/AndroidManifest.xml` is the sideloaded variant's manifest without the
package attribute, which AGP 8 refuses because it takes the application id from
`build.gradle.kts`. Soong reads it from the manifest and has nowhere else to
look, so the two cannot share a file - keep them in step.

## Documentation

- [docs/kernel.md](docs/kernel.md) - what MediaTek's port controller publishes,
  the three things its interface loses on the way to userspace, why the
  upstream power delivery class is registered and empty, what the charging
  stack adds, and the two changes that would close the gap.

## Licence

Apache 2.0, and derived from
[QcomPdInfo](https://github.com/WitAqua/packages_apps_QcomPdInfo) under the
same licence.
