package com.pocketkeyboard.hid

import com.pocketkeyboard.root.RootShell

enum class GadgetBackend {
    CONFIGFS,
    EXISTING_HIDG,
    ANDROID_USB,
    NONE
}

data class GadgetEnvironment(
    val backend: GadgetBackend,
    val configFsPath: String? = null,
    val hidDevicePath: String? = null,
    val mouseDevicePath: String? = null,
    val udcName: String? = null,
    val hasGadgetFs: Boolean = false
)

data class EnabledHidDevices(
    val keyboardPath: String,
    val mousePath: String?
)

/**
 * Manages the USB HID gadget lifecycle (Keyboard + Mouse) across Linux ConfigFS,
 * pre-exposed /dev/hidg* nodes, and legacy android_usb kernels.
 */
object UsbGadgetManager {

    private const val GADGET_NAME = "pocketkeyboard"
    private var activeConfigFsPath: String? = null
    private var activeGadgetName: String? = null
    private var activeConfigDirName: String? = null
    private var previousUsbConfig: String? = null

    // Standard USB HID Keyboard Report Descriptor (63 bytes, 8-byte report)
    private val KEYBOARD_REPORT_DESCRIPTOR = byteArrayOf(
        0x05, 0x01,                      // Usage Page (Generic Desktop)
        0x09, 0x06,                      // Usage (Keyboard)
        0xA1.toByte(), 0x01,             // Collection (Application)
        0x05, 0x07,                      //   Usage Page (Key Codes)
        0x19, 0xE0.toByte(),             //   Usage Minimum (224) - Left Control
        0x29, 0xE7.toByte(),             //   Usage Maximum (231) - Right GUI
        0x15, 0x00,                      //   Logical Minimum (0)
        0x25, 0x01,                      //   Logical Maximum (1)
        0x75, 0x01,                      //   Report Size (1)
        0x95.toByte(), 0x08,             //   Report Count (8)
        0x81.toByte(), 0x02,             //   Input (Data, Variable, Absolute) - Modifier byte
        0x95.toByte(), 0x01,             //   Report Count (1)
        0x75, 0x08,                      //   Report Size (8)
        0x81.toByte(), 0x01,             //   Input (Constant) - Reserved byte
        0x95.toByte(), 0x05,             //   Report Count (5)
        0x75, 0x01,                      //   Report Size (1)
        0x05, 0x08,                      //   Usage Page (LEDs)
        0x19, 0x01,                      //   Usage Minimum (1) - Num Lock
        0x29, 0x05,                      //   Usage Maximum (5) - Kana
        0x91.toByte(), 0x02,             //   Output (Data, Variable, Absolute) - LED report
        0x95.toByte(), 0x01,             //   Report Count (1)
        0x75, 0x03,                      //   Report Size (3)
        0x91.toByte(), 0x01,             //   Output (Constant) - LED padding
        0x95.toByte(), 0x06,             //   Report Count (6)
        0x75, 0x08,                      //   Report Size (8)
        0x15, 0x00,                      //   Logical Minimum (0)
        0x25, 0x65,                      //   Logical Maximum (101)
        0x05, 0x07,                      //   Usage Page (Key Codes)
        0x19, 0x00,                      //   Usage Minimum (0)
        0x29, 0x65,                      //   Usage Maximum (101)
        0x81.toByte(), 0x00,             //   Input (Data, Array) - Key array (6 keys)
        0xC0.toByte()                    // End Collection
    )

    // Standard USB HID 3-Button Mouse + Wheel Report Descriptor (52 bytes, 4-byte report)
    // [0] Buttons (bit 0=Left, bit 1=Right, bit 2=Middle), [1] dX, [2] dY, [3] Wheel
    private val MOUSE_REPORT_DESCRIPTOR = byteArrayOf(
        0x05, 0x01,                      // Usage Page (Generic Desktop)
        0x09, 0x02,                      // Usage (Mouse)
        0xA1.toByte(), 0x01,             // Collection (Application)
        0x09, 0x01,                      //   Usage (Pointer)
        0xA1.toByte(), 0x00,             //   Collection (Physical)
        0x05, 0x09,                      //     Usage Page (Buttons)
        0x19, 0x01,                      //     Usage Minimum (1)
        0x29, 0x03,                      //     Usage Maximum (3)
        0x15, 0x00,                      //     Logical Minimum (0)
        0x25, 0x01,                      //     Logical Maximum (1)
        0x95.toByte(), 0x03,             //     Report Count (3)
        0x75, 0x01,                      //     Report Size (1)
        0x81.toByte(), 0x02,             //     Input (Data, Variable, Absolute)
        0x95.toByte(), 0x01,             //     Report Count (1)
        0x75, 0x05,                      //     Report Size (5)
        0x81.toByte(), 0x01,             //     Input (Constant) - 5-bit padding
        0x05, 0x01,                      //     Usage Page (Generic Desktop)
        0x09, 0x30,                      //     Usage (X)
        0x09, 0x31,                      //     Usage (Y)
        0x09, 0x38,                      //     Usage (Wheel)
        0x15, 0x81.toByte(),             //     Logical Minimum (-127)
        0x25, 0x7F,                      //     Logical Maximum (127)
        0x75, 0x08,                      //     Report Size (8)
        0x95.toByte(), 0x03,             //     Report Count (3)
        0x81.toByte(), 0x06,             //     Input (Data, Variable, Relative)
        0xC0.toByte(),                   //   End Collection (Physical)
        0xC0.toByte()                    // End Collection (Application)
    )

    suspend fun detectEnvironment(): GadgetEnvironment {
        val probeScript = """
            CFG=""
            if [ -d /config/usb_gadget ]; then
                CFG="/config/usb_gadget"
            elif [ -d /sys/kernel/config/usb_gadget ]; then
                CFG="/sys/kernel/config/usb_gadget"
            else
                mount -t configfs none /sys/kernel/config 2>/dev/null || true
                if [ -d /sys/kernel/config/usb_gadget ]; then
                    CFG="/sys/kernel/config/usb_gadget"
                elif [ -d /config/usb_gadget ]; then
                    CFG="/config/usb_gadget"
                fi
            fi
            HIDG0=$(ls /dev/hidg* 2>/dev/null | sed -n '1p')
            HIDG1=$(ls /dev/hidg* 2>/dev/null | sed -n '2p')
            UDC=$(ls /sys/class/udc 2>/dev/null | head -1)
            AUSB="no"
            [ -d /sys/class/android_usb/android0 ] && AUSB="yes"
            GFS="no"
            if [ -d /dev/gadget ] || grep -q gadgetfs /proc/filesystems 2>/dev/null; then
                GFS="yes"
            fi
            echo "CFG=${'$'}CFG"
            echo "HIDG0=${'$'}HIDG0"
            echo "HIDG1=${'$'}HIDG1"
            echo "UDC=${'$'}UDC"
            echo "AUSB=${'$'}AUSB"
            echo "GFS=${'$'}GFS"
        """.trimIndent()

        val result = RootShell.execute(probeScript)
        if (result.isFailure) {
            return GadgetEnvironment(GadgetBackend.NONE)
        }

        val map = result.getOrDefault("").lines()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                else null
            }.toMap()

        val cfg = map["CFG"]?.takeIf { it.isNotBlank() }
        val hidg0 = map["HIDG0"]?.takeIf { it.isNotBlank() }
        val hidg1 = map["HIDG1"]?.takeIf { it.isNotBlank() }
        val udc = map["UDC"]?.takeIf { it.isNotBlank() }
        val ausb = map["AUSB"] == "yes"
        val gfs = map["GFS"] == "yes"

        val backend = when {
            cfg != null -> GadgetBackend.CONFIGFS
            hidg0 != null -> GadgetBackend.EXISTING_HIDG
            ausb -> GadgetBackend.ANDROID_USB
            else -> GadgetBackend.NONE
        }

        return GadgetEnvironment(
            backend = backend,
            configFsPath = cfg,
            hidDevicePath = hidg0,
            mouseDevicePath = hidg1,
            udcName = udc,
            hasGadgetFs = gfs
        )
    }

    suspend fun enable(): Result<EnabledHidDevices> {
        val env = detectEnvironment()

        if (env.backend == GadgetBackend.NONE) {
            val gfsNote = if (env.hasGadgetFs) {
                " GadgetFS (/dev/gadget) was detected, which requires a userspace EP0 daemon; kernel ConfigFS or /dev/hidg0 is required."
            } else ""
            return Result.failure(
                Exception("No USB HID gadget interface found (checked /config/usb_gadget, /sys/kernel/config/usb_gadget, /dev/hidg*, and android_usb).$gfsNote")
            )
        }

        previousUsbConfig = RootShell.execute("getprop sys.usb.config").getOrNull()?.trim()

        return when (env.backend) {
            GadgetBackend.CONFIGFS -> enableViaConfigFs(env.configFsPath!!)
            GadgetBackend.ANDROID_USB -> enableViaAndroidUsb()
            GadgetBackend.EXISTING_HIDG -> {
                val kbd = env.hidDevicePath!!
                val mouse = env.mouseDevicePath
                RootShell.execute("chmod 666 \"$kbd\" 2>/dev/null || true")
                if (mouse != null) RootShell.execute("chmod 666 \"$mouse\" 2>/dev/null || true")
                Result.success(EnabledHidDevices(kbd, mouse))
            }
            GadgetBackend.NONE -> Result.failure(Exception("Unsupported USB gadget backend"))
        }
    }

    private suspend fun enableViaConfigFs(configFsBase: String): Result<EnabledHidDevices> {
        val standaloneResult = setupConfigFsGadget(configFsBase, GADGET_NAME, isExistingG1 = false)
        if (standaloneResult.isSuccess) {
            return standaloneResult
        }

        disableQuiet("$configFsBase/$GADGET_NAME", "c.1")

        val g1Exists = RootShell.execute("test -d $configFsBase/g1 && echo yes || echo no")
            .getOrDefault("no") == "yes"
        if (g1Exists) {
            val g1Result = setupConfigFsGadget(configFsBase, "g1", isExistingG1 = true)
            if (g1Result.isSuccess) {
                return g1Result
            }
        }

        return standaloneResult
    }

    private suspend fun setupConfigFsGadget(
        configFsBase: String,
        gadgetName: String,
        isExistingG1: Boolean
    ): Result<EnabledHidDevices> {
        val gadgetDir = "$configFsBase/$gadgetName"
        val kbdHex = KEYBOARD_REPORT_DESCRIPTOR.joinToString("") { "\\x%02x".format(it) }
        val mouseHex = MOUSE_REPORT_DESCRIPTOR.joinToString("") { "\\x%02x".format(it) }

        val configSubDir = if (isExistingG1) {
            val detected = RootShell.execute(
                "ls $gadgetDir/configs 2>/dev/null | head -1"
            ).getOrNull()?.trim()
            if (!detected.isNullOrBlank()) detected else "b.1"
        } else {
            disableQuiet(gadgetDir, "c.1")
            "c.1"
        }

        val commands = mutableListOf(
            "setprop sys.usb.config none 2>/dev/null || true",
            "sleep 0.3",
            "for udc_file in $configFsBase/*/UDC; do " +
                "[ -f \"\$udc_file\" ] && echo '' > \"\$udc_file\" 2>/dev/null; " +
            "done || true"
        )

        if (!isExistingG1) {
            commands.addAll(
                listOf(
                    "mkdir -p $gadgetDir",
                    "echo 0x1d6b > $gadgetDir/idVendor",
                    "echo 0x0104 > $gadgetDir/idProduct",
                    "echo 0x0100 > $gadgetDir/bcdDevice",
                    "echo 0x0200 > $gadgetDir/bcdUSB",
                    "mkdir -p $gadgetDir/strings/0x409",
                    "echo 'PKTB001' > $gadgetDir/strings/0x409/serialnumber",
                    "echo 'PocketKeyboard' > $gadgetDir/strings/0x409/manufacturer",
                    "echo 'PocketKeyboard' > $gadgetDir/strings/0x409/product"
                )
            )
        } else {
            commands.add("rm -f $gadgetDir/configs/$configSubDir/hid.usb0 $gadgetDir/configs/$configSubDir/hid.usb1 2>/dev/null || true")
        }

        commands.addAll(
            listOf(
                // 1. Keyboard HID function (hid.usb0)
                "mkdir -p $gadgetDir/functions/hid.usb0",
                "echo 1 > $gadgetDir/functions/hid.usb0/protocol",
                "echo 1 > $gadgetDir/functions/hid.usb0/subclass",
                "echo 8 > $gadgetDir/functions/hid.usb0/report_length",
                "printf '${kbdHex}' > $gadgetDir/functions/hid.usb0/report_desc",

                // Ensure configuration exists
                "mkdir -p $gadgetDir/configs/$configSubDir/strings/0x409",
                "echo 'HID Combo' > $gadgetDir/configs/$configSubDir/strings/0x409/configuration 2>/dev/null || true",
                "echo 120 > $gadgetDir/configs/$configSubDir/MaxPower 2>/dev/null || true",

                // Link Keyboard function
                "ln -s $gadgetDir/functions/hid.usb0 $gadgetDir/configs/$configSubDir/hid.usb0",

                // 2. Mouse HID function (hid.usb1) - best effort so keyboard still works if kernel limits to 1 hid
                "if mkdir -p $gadgetDir/functions/hid.usb1 2>/dev/null; then " +
                    "echo 2 > $gadgetDir/functions/hid.usb1/protocol; " +
                    "echo 1 > $gadgetDir/functions/hid.usb1/subclass; " +
                    "echo 4 > $gadgetDir/functions/hid.usb1/report_length; " +
                    "printf '${mouseHex}' > $gadgetDir/functions/hid.usb1/report_desc; " +
                    "ln -s $gadgetDir/functions/hid.usb1 $gadgetDir/configs/$configSubDir/hid.usb1 2>/dev/null || true; " +
                "fi",

                // Bind to UDC
                "UDC=\$(ls /sys/class/udc 2>/dev/null | head -1)",
                "if [ -n \"\$UDC\" ]; then echo \"\$UDC\" > $gadgetDir/UDC; else echo 'NO_UDC' >&2; exit 1; fi",

                // Wait for /dev/hidg* nodes and open permissions
                "sleep 0.4",
                "HIDG0=\$(ls /dev/hidg* 2>/dev/null | sed -n '1p')",
                "HIDG1=\$(ls /dev/hidg* 2>/dev/null | sed -n '2p')",
                "if [ -z \"\$HIDG0\" ]; then echo 'NO_HIDG_NODE' >&2; exit 1; fi",
                "chmod 666 \"\$HIDG0\" 2>/dev/null || true",
                "[ -n \"\$HIDG1\" ] && chmod 666 \"\$HIDG1\" 2>/dev/null || true",
                "echo \"KBD=\$HIDG0\"",
                "echo \"MOUSE=\$HIDG1\""
            )
        )

        val result = RootShell.executeBatch(commands)
        return result.mapCatching { output ->
            val kbd = output.lines().firstOrNull { it.startsWith("KBD=") }
                ?.substringAfter("KBD=")?.trim()?.takeIf { it.isNotBlank() }
                ?: throw Exception("HID device node (/dev/hidg*) was not created by the kernel.")
            val mouse = output.lines().firstOrNull { it.startsWith("MOUSE=") }
                ?.substringAfter("MOUSE=")?.trim()?.takeIf { it.isNotBlank() }
            activeConfigFsPath = configFsBase
            activeGadgetName = gadgetName
            activeConfigDirName = configSubDir
            EnabledHidDevices(keyboardPath = kbd, mousePath = mouse)
        }
    }

    private suspend fun enableViaAndroidUsb(): Result<EnabledHidDevices> {
        val commands = listOf(
            "setprop sys.usb.config none 2>/dev/null || true",
            "echo 0 > /sys/class/android_usb/android0/enable 2>/dev/null || true",
            "echo hid > /sys/class/android_usb/android0/functions",
            "echo 1 > /sys/class/android_usb/android0/enable",
            "sleep 0.4",
            "HIDG0=\$(ls /dev/hidg* 2>/dev/null | sed -n '1p')",
            "HIDG1=\$(ls /dev/hidg* 2>/dev/null | sed -n '2p')",
            "if [ -z \"\$HIDG0\" ]; then echo 'NO_HIDG_NODE' >&2; exit 1; fi",
            "chmod 666 \"\$HIDG0\" 2>/dev/null || true",
            "[ -n \"\$HIDG1\" ] && chmod 666 \"\$HIDG1\" 2>/dev/null || true",
            "echo \"KBD=\$HIDG0\"",
            "echo \"MOUSE=\$HIDG1\""
        )

        val result = RootShell.executeBatch(commands)
        return result.mapCatching { output ->
            val kbd = output.lines().firstOrNull { it.startsWith("KBD=") }
                ?.substringAfter("KBD=")?.trim()?.takeIf { it.isNotBlank() }
                ?: throw Exception("Legacy android_usb did not expose /dev/hidg*.")
            val mouse = output.lines().firstOrNull { it.startsWith("MOUSE=") }
                ?.substringAfter("MOUSE=")?.trim()?.takeIf { it.isNotBlank() }
            EnabledHidDevices(keyboardPath = kbd, mousePath = mouse)
        }
    }

    suspend fun disable(): Result<Unit> {
        val base = activeConfigFsPath
        val name = activeGadgetName
        val cfgName = activeConfigDirName ?: "c.1"
        val commands = mutableListOf<String>()

        if (base != null && name != null) {
            val gadgetDir = "$base/$name"
            commands.add("echo '' > $gadgetDir/UDC 2>/dev/null || true")
            commands.add("rm -f $gadgetDir/configs/$cfgName/hid.usb0 $gadgetDir/configs/$cfgName/hid.usb1 2>/dev/null || true")
            if (name != "g1") {
                commands.addAll(
                    listOf(
                        "rmdir $gadgetDir/configs/$cfgName/strings/0x409 2>/dev/null || true",
                        "rmdir $gadgetDir/configs/$cfgName 2>/dev/null || true",
                        "rmdir $gadgetDir/functions/hid.usb0 2>/dev/null || true",
                        "rmdir $gadgetDir/functions/hid.usb1 2>/dev/null || true",
                        "rmdir $gadgetDir/strings/0x409 2>/dev/null || true",
                        "rmdir $gadgetDir 2>/dev/null || true"
                    )
                )
            } else {
                commands.add("rmdir $gadgetDir/functions/hid.usb0 2>/dev/null || true")
                commands.add("rmdir $gadgetDir/functions/hid.usb1 2>/dev/null || true")
            }
        }

        val restoreCfg = previousUsbConfig?.takeIf { it.isNotBlank() && it != "none" }
        if (restoreCfg != null) {
            commands.add("setprop sys.usb.config $restoreCfg 2>/dev/null || true")
        }

        activeConfigFsPath = null
        activeGadgetName = null
        activeConfigDirName = null
        if (commands.isEmpty()) return Result.success(Unit)
        return RootShell.executeBatch(commands).map { }
    }

    private suspend fun disableQuiet(gadgetDir: String, cfgName: String) {
        val commands = listOf(
            "echo '' > $gadgetDir/UDC 2>/dev/null || true",
            "rm -f $gadgetDir/configs/$cfgName/hid.usb0 $gadgetDir/configs/$cfgName/hid.usb1 2>/dev/null || true",
            "rmdir $gadgetDir/configs/$cfgName/strings/0x409 2>/dev/null || true",
            "rmdir $gadgetDir/configs/$cfgName 2>/dev/null || true",
            "rmdir $gadgetDir/functions/hid.usb0 2>/dev/null || true",
            "rmdir $gadgetDir/functions/hid.usb1 2>/dev/null || true",
            "rmdir $gadgetDir/strings/0x409 2>/dev/null || true",
            "rmdir $gadgetDir 2>/dev/null || true"
        )
        try {
            RootShell.executeBatch(commands)
        } catch (_: Exception) { }
    }
}
