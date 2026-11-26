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
    val gamepadDevicePath: String? = null,
    val udcName: String? = null,
    val hasGadgetFs: Boolean = false
)

data class EnabledHidDevices(
    val keyboardPath: String,
    val mousePath: String?,
    val gamepadPath: String? = null
)

/**
 * Manages the USB HID gadget lifecycle (Keyboard + Mouse + Gamepad) across Linux ConfigFS,
 * pre-exposed /dev/hidg* nodes, and legacy android_usb kernels.
 */
object UsbGadgetManager {

    private const val GADGET_NAME = "pocketkeyboard"
    private var activeConfigFsPath: String? = null
    private var activeGadgetName: String? = null
    private var activeConfigDirName: String? = null
    private var previousUsbConfig: String? = null

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
            HIDG2=$(ls /dev/hidg* 2>/dev/null | sed -n '3p')
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
            echo "HIDG2=${'$'}HIDG2"
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
        val hidg2 = map["HIDG2"]?.takeIf { it.isNotBlank() }
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
            gamepadDevicePath = hidg2,
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
                val gamepad = env.gamepadDevicePath
                RootShell.execute("chmod 666 \"$kbd\" 2>/dev/null || true")
                if (mouse != null) RootShell.execute("chmod 666 \"$mouse\" 2>/dev/null || true")
                if (gamepad != null) RootShell.execute("chmod 666 \"$gamepad\" 2>/dev/null || true")
                Result.success(EnabledHidDevices(kbd, mouse, gamepad))
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
        val kbdHex = HidDescriptors.USB_KEYBOARD_REPORT_DESCRIPTOR.joinToString("") { "\\x%02x".format(it) }
        val mouseHex = HidDescriptors.USB_MOUSE_REPORT_DESCRIPTOR.joinToString("") { "\\x%02x".format(it) }
        val gamepadHex = HidDescriptors.USB_GAMEPAD_REPORT_DESCRIPTOR.joinToString("") { "\\x%02x".format(it) }

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
            commands.add(
                "rm -f $gadgetDir/configs/$configSubDir/hid.usb0 " +
                    "$gadgetDir/configs/$configSubDir/hid.usb1 " +
                    "$gadgetDir/configs/$configSubDir/hid.usb2 2>/dev/null || true"
            )
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

                // 2. Mouse HID function (hid.usb1) - best effort
                "if mkdir -p $gadgetDir/functions/hid.usb1 2>/dev/null; then " +
                    "echo 2 > $gadgetDir/functions/hid.usb1/protocol; " +
                    "echo 1 > $gadgetDir/functions/hid.usb1/subclass; " +
                    "echo 4 > $gadgetDir/functions/hid.usb1/report_length; " +
                    "printf '${mouseHex}' > $gadgetDir/functions/hid.usb1/report_desc; " +
                    "ln -s $gadgetDir/functions/hid.usb1 $gadgetDir/configs/$configSubDir/hid.usb1 2>/dev/null || true; " +
                "fi",

                // 3. Gamepad HID function (hid.usb2) - best effort
                "if mkdir -p $gadgetDir/functions/hid.usb2 2>/dev/null; then " +
                    "echo 0 > $gadgetDir/functions/hid.usb2/protocol; " +
                    "echo 0 > $gadgetDir/functions/hid.usb2/subclass; " +
                    "echo 9 > $gadgetDir/functions/hid.usb2/report_length; " +
                    "printf '${gamepadHex}' > $gadgetDir/functions/hid.usb2/report_desc; " +
                    "ln -s $gadgetDir/functions/hid.usb2 $gadgetDir/configs/$configSubDir/hid.usb2 2>/dev/null || true; " +
                "fi",

                // Bind to UDC
                "UDC=\$(ls /sys/class/udc 2>/dev/null | head -1)",
                "if [ -n \"\$UDC\" ]; then echo \"\$UDC\" > $gadgetDir/UDC; else echo 'NO_UDC' >&2; exit 1; fi",

                // Wait for /dev/hidg* nodes and open permissions
                "sleep 0.4",
                "HIDG0=\$(ls /dev/hidg* 2>/dev/null | sed -n '1p')",
                "HIDG1=\$(ls /dev/hidg* 2>/dev/null | sed -n '2p')",
                "HIDG2=\$(ls /dev/hidg* 2>/dev/null | sed -n '3p')",
                "if [ -z \"\$HIDG0\" ]; then echo 'NO_HIDG_NODE' >&2; exit 1; fi",
                "chmod 666 \"\$HIDG0\" 2>/dev/null || true",
                "[ -n \"\$HIDG1\" ] && chmod 666 \"\$HIDG1\" 2>/dev/null || true",
                "[ -n \"\$HIDG2\" ] && chmod 666 \"\$HIDG2\" 2>/dev/null || true",
                "echo \"KBD=\$HIDG0\"",
                "echo \"MOUSE=\$HIDG1\"",
                "echo \"GAMEPAD=\$HIDG2\""
            )
        )

        val result = RootShell.executeBatch(commands)
        return result.mapCatching { output ->
            val kbd = output.lines().firstOrNull { it.startsWith("KBD=") }
                ?.substringAfter("KBD=")?.trim()?.takeIf { it.isNotBlank() }
                ?: throw Exception("HID device node (/dev/hidg*) was not created by the kernel.")
            val mouse = output.lines().firstOrNull { it.startsWith("MOUSE=") }
                ?.substringAfter("MOUSE=")?.trim()?.takeIf { it.isNotBlank() }
            val gamepad = output.lines().firstOrNull { it.startsWith("GAMEPAD=") }
                ?.substringAfter("GAMEPAD=")?.trim()?.takeIf { it.isNotBlank() }
            activeConfigFsPath = configFsBase
            activeGadgetName = gadgetName
            activeConfigDirName = configSubDir
            EnabledHidDevices(keyboardPath = kbd, mousePath = mouse, gamepadPath = gamepad)
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
            "HIDG2=\$(ls /dev/hidg* 2>/dev/null | sed -n '3p')",
            "if [ -z \"\$HIDG0\" ]; then echo 'NO_HIDG_NODE' >&2; exit 1; fi",
            "chmod 666 \"\$HIDG0\" 2>/dev/null || true",
            "[ -n \"\$HIDG1\" ] && chmod 666 \"\$HIDG1\" 2>/dev/null || true",
            "[ -n \"\$HIDG2\" ] && chmod 666 \"\$HIDG2\" 2>/dev/null || true",
            "echo \"KBD=\$HIDG0\"",
            "echo \"MOUSE=\$HIDG1\"",
            "echo \"GAMEPAD=\$HIDG2\""
        )

        val result = RootShell.executeBatch(commands)
        return result.mapCatching { output ->
            val kbd = output.lines().firstOrNull { it.startsWith("KBD=") }
                ?.substringAfter("KBD=")?.trim()?.takeIf { it.isNotBlank() }
                ?: throw Exception("Legacy android_usb did not expose /dev/hidg*.")
            val mouse = output.lines().firstOrNull { it.startsWith("MOUSE=") }
                ?.substringAfter("MOUSE=")?.trim()?.takeIf { it.isNotBlank() }
            val gamepad = output.lines().firstOrNull { it.startsWith("GAMEPAD=") }
                ?.substringAfter("GAMEPAD=")?.trim()?.takeIf { it.isNotBlank() }
            EnabledHidDevices(keyboardPath = kbd, mousePath = mouse, gamepadPath = gamepad)
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
            commands.add(
                "rm -f $gadgetDir/configs/$cfgName/hid.usb0 " +
                    "$gadgetDir/configs/$cfgName/hid.usb1 " +
                    "$gadgetDir/configs/$cfgName/hid.usb2 2>/dev/null || true"
            )
            if (name != "g1") {
                commands.addAll(
                    listOf(
                        "rmdir $gadgetDir/configs/$cfgName/strings/0x409 2>/dev/null || true",
                        "rmdir $gadgetDir/configs/$cfgName 2>/dev/null || true",
                        "rmdir $gadgetDir/functions/hid.usb0 2>/dev/null || true",
                        "rmdir $gadgetDir/functions/hid.usb1 2>/dev/null || true",
                        "rmdir $gadgetDir/functions/hid.usb2 2>/dev/null || true",
                        "rmdir $gadgetDir/strings/0x409 2>/dev/null || true",
                        "rmdir $gadgetDir 2>/dev/null || true"
                    )
                )
            } else {
                commands.add("rmdir $gadgetDir/functions/hid.usb0 2>/dev/null || true")
                commands.add("rmdir $gadgetDir/functions/hid.usb1 2>/dev/null || true")
                commands.add("rmdir $gadgetDir/functions/hid.usb2 2>/dev/null || true")
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
            "rm -f $gadgetDir/configs/$cfgName/hid.usb0 $gadgetDir/configs/$cfgName/hid.usb1 $gadgetDir/configs/$cfgName/hid.usb2 2>/dev/null || true",
            "rmdir $gadgetDir/configs/$cfgName/strings/0x409 2>/dev/null || true",
            "rmdir $gadgetDir/configs/$cfgName 2>/dev/null || true",
            "rmdir $gadgetDir/functions/hid.usb0 2>/dev/null || true",
            "rmdir $gadgetDir/functions/hid.usb1 2>/dev/null || true",
            "rmdir $gadgetDir/functions/hid.usb2 2>/dev/null || true",
            "rmdir $gadgetDir/strings/0x409 2>/dev/null || true",
            "rmdir $gadgetDir 2>/dev/null || true"
        )
        try {
            RootShell.executeBatch(commands)
        } catch (_: Exception) { }
    }
}
