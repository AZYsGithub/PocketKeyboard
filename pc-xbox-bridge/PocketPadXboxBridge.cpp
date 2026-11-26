#define WIN32_LEAN_AND_MEAN
#define NOMINMAX

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <initguid.h>
#include <ws2bth.h>
#include <bluetoothapis.h>
#include <iphlpapi.h>
#include <setupapi.h>
#include <timeapi.h>
#include <winioctl.h>

extern "C" {
#include <hidsdi.h>
#include <hidpi.h>
}

#include <algorithm>
#include <array>
#include <atomic>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cwctype>
#include <string>
#include <vector>

// Official Nefarius ViGEmBus device interface GUID:
// {96E42B22-F5E9-42F8-B043-ED0F932F014F}
DEFINE_GUID(
    GUID_DEVINTERFACE_BUSENUM_VIGEM,
    0x96E42B22, 0xF5E9, 0x42F8,
    0xB0, 0x43, 0xED, 0x0F, 0x93, 0x2F, 0x01, 0x4F
);

// PocketKeyboard Direct Bluetooth RFCOMM Gamepad Bridge UUID:
// 706b6264-5842-4f58-5041-440000000001 ("pkbdXBOXPAD\x00\x00\x00\x00\x01")
DEFINE_GUID(
    GUID_POCKETPAD_RFCOMM_BRIDGE,
    0x706B6264, 0x5842, 0x4F58,
    0x50, 0x41, 0x44, 0x00, 0x00, 0x00, 0x00, 0x01
);

#ifndef FILE_DEVICE_BUS_EXTENDER
#define FILE_DEVICE_BUS_EXTENDER 0x0000002a
#endif

#ifndef HIDP_STATUS_SUCCESS
#define HIDP_STATUS_SUCCESS ((NTSTATUS)0x00110000L)
#endif

#define VIGEM_COMMON_VERSION            0x0001
#define FILE_DEVICE_BUSENUM             FILE_DEVICE_BUS_EXTENDER
#define BUSENUM_W_IOCTL(_index_)        CTL_CODE(FILE_DEVICE_BUSENUM, _index_, METHOD_BUFFERED, FILE_WRITE_DATA)
#define IOCTL_VIGEM_BASE                0x801

#define IOCTL_VIGEM_PLUGIN_TARGET       BUSENUM_W_IOCTL(IOCTL_VIGEM_BASE + 0x000)
#define IOCTL_VIGEM_UNPLUG_TARGET       BUSENUM_W_IOCTL(IOCTL_VIGEM_BASE + 0x001)
#define IOCTL_VIGEM_CHECK_VERSION       BUSENUM_W_IOCTL(IOCTL_VIGEM_BASE + 0x002)
#define IOCTL_VIGEM_WAIT_DEVICE_READY   BUSENUM_W_IOCTL(IOCTL_VIGEM_BASE + 0x003)
#define IOCTL_XUSB_SUBMIT_REPORT        BUSENUM_W_IOCTL(IOCTL_VIGEM_BASE + 0x201)

// Legacy Bluetooth RFCOMM Gamepad-only frame constants
static constexpr BYTE BT_BRIDGE_HEADER_0 = 0xA5;
static constexpr BYTE BT_BRIDGE_HEADER_1 = 0x5A;
static constexpr size_t BT_BRIDGE_FRAME_SIZE = 12;

// PocketBridge v2 constants (USB Tethering UDP & ADB TCP)
static constexpr u_short POCKETBRIDGE_PORT = 27189;
static constexpr BYTE V2_HEADER_0 = 0xA5;
static constexpr BYTE V2_HEADER_1 = 0x5B;
static constexpr BYTE V2_TYPE_GAMEPAD = 0x01;
static constexpr BYTE V2_TYPE_MOUSE = 0x02;
static constexpr BYTE V2_TYPE_KEYBOARD = 0x03;
static constexpr BYTE V2_TYPE_PING = 0x04;
static constexpr BYTE V2_TYPE_PONG = 0x05;
static constexpr BYTE V2_PING_FRAME[5] = { V2_HEADER_0, V2_HEADER_1, V2_TYPE_PING, 0x00, V2_TYPE_PING };
static constexpr BYTE V2_PONG_FRAME[5] = { V2_HEADER_0, V2_HEADER_1, V2_TYPE_PONG, 0x00, V2_TYPE_PONG };

enum VIGEM_TARGET_TYPE_VAL : ULONG {
    VIGEM_TARGET_XBOX360_WIRED = 0
};

enum XUSB_BUTTON : USHORT {
    XUSB_GAMEPAD_DPAD_UP        = 0x0001,
    XUSB_GAMEPAD_DPAD_DOWN      = 0x0002,
    XUSB_GAMEPAD_DPAD_LEFT      = 0x0004,
    XUSB_GAMEPAD_DPAD_RIGHT     = 0x0008,
    XUSB_GAMEPAD_START          = 0x0010,
    XUSB_GAMEPAD_BACK           = 0x0020,
    XUSB_GAMEPAD_LEFT_THUMB     = 0x0040,
    XUSB_GAMEPAD_RIGHT_THUMB    = 0x0080,
    XUSB_GAMEPAD_LEFT_SHOULDER  = 0x0100,
    XUSB_GAMEPAD_RIGHT_SHOULDER = 0x0200,
    XUSB_GAMEPAD_GUIDE          = 0x0400,
    XUSB_GAMEPAD_A              = 0x1000,
    XUSB_GAMEPAD_B              = 0x2000,
    XUSB_GAMEPAD_X              = 0x4000,
    XUSB_GAMEPAD_Y              = 0x8000
};

struct VIGEM_CHECK_VERSION {
    ULONG Size;
    ULONG Version;
};

struct VIGEM_PLUGIN_TARGET {
    ULONG Size;
    ULONG SerialNo;
    ULONG TargetType;
    USHORT VendorId;
    USHORT ProductId;
};

struct VIGEM_WAIT_DEVICE_READY {
    ULONG Size;
    ULONG SerialNo;
};

struct VIGEM_UNPLUG_TARGET {
    ULONG Size;
    ULONG SerialNo;
};

struct XUSB_REPORT {
    USHORT wButtons;
    BYTE bLeftTrigger;
    BYTE bRightTrigger;
    SHORT sThumbLX;
    SHORT sThumbLY;
    SHORT sThumbRX;
    SHORT sThumbRY;
};

struct XUSB_SUBMIT_REPORT {
    ULONG Size;
    ULONG SerialNo;
    XUSB_REPORT Report;
};

static_assert(sizeof(VIGEM_CHECK_VERSION) == 8, "Unexpected VIGEM_CHECK_VERSION size");
static_assert(sizeof(VIGEM_PLUGIN_TARGET) == 16, "Unexpected VIGEM_PLUGIN_TARGET size");
static_assert(sizeof(VIGEM_WAIT_DEVICE_READY) == 8, "Unexpected VIGEM_WAIT_DEVICE_READY size");
static_assert(sizeof(VIGEM_UNPLUG_TARGET) == 8, "Unexpected VIGEM_UNPLUG_TARGET size");
static_assert(sizeof(XUSB_REPORT) == 12, "Unexpected XUSB_REPORT size");
static_assert(sizeof(XUSB_SUBMIT_REPORT) == 20, "Unexpected XUSB_SUBMIT_REPORT size");

static std::atomic<bool> g_running{true};

class ScopedTimerResolution {
public:
    explicit ScopedTimerResolution(UINT periodMs) : periodMs_(periodMs) {
        active_ = (timeBeginPeriod(periodMs_) == TIMERR_NOERROR);
    }
    ~ScopedTimerResolution() {
        if (active_) {
            timeEndPeriod(periodMs_);
        }
    }
private:
    UINT periodMs_ = 1;
    bool active_ = false;
};

static BOOL WINAPI consoleCtrlHandler(DWORD ctrlType) {
    switch (ctrlType) {
        case CTRL_C_EVENT:
        case CTRL_BREAK_EVENT:
        case CTRL_CLOSE_EVENT:
        case CTRL_LOGOFF_EVENT:
        case CTRL_SHUTDOWN_EVENT:
            g_running.store(false);
            return TRUE;
        default:
            return FALSE;
    }
}

static std::wstring toLowerW(std::wstring s) {
    for (wchar_t& ch : s) {
        ch = static_cast<wchar_t>(towlower(ch));
    }
    return s;
}

static std::string formatBthAddr(BTH_ADDR addr) {
    char buf[24]{};
    std::snprintf(
        buf,
        sizeof(buf),
        "%02X:%02X:%02X:%02X:%02X:%02X",
        static_cast<unsigned>((addr >> 40) & 0xFF),
        static_cast<unsigned>((addr >> 32) & 0xFF),
        static_cast<unsigned>((addr >> 24) & 0xFF),
        static_cast<unsigned>((addr >> 16) & 0xFF),
        static_cast<unsigned>((addr >> 8) & 0xFF),
        static_cast<unsigned>(addr & 0xFF)
    );
    return std::string(buf);
}

static std::string formatMacString(const std::wstring& hex12) {
    if (hex12.size() != 12) {
        return std::string(hex12.begin(), hex12.end());
    }
    std::string out;
    out.reserve(17);
    for (size_t i = 0; i < 12; ++i) {
        if (i > 0 && (i % 2) == 0) out.push_back(':');
        wchar_t wc = hex12[i];
        out.push_back(static_cast<char>(std::toupper(static_cast<unsigned char>(wc))));
    }
    return out;
}

static bool containsBytes(const std::vector<BYTE>& haystack, const BYTE* needle, size_t needleLen) {
    if (needleLen == 0 || haystack.size() < needleLen) return false;
    auto it = std::search(haystack.begin(), haystack.end(), needle, needle + needleLen);
    return it != haystack.end();
}

static bool syncIoctl(
    HANDLE device,
    DWORD ioctl,
    void* inBuffer,
    DWORD inSize,
    HANDLE eventHandle,
    DWORD* lastErrOut = nullptr
) {
    OVERLAPPED ol{};
    ol.hEvent = eventHandle;
    ResetEvent(eventHandle);

    DWORD transferred = 0;
    BOOL ok = DeviceIoControl(device, ioctl, inBuffer, inSize, nullptr, 0, &transferred, &ol);
    if (!ok) {
        DWORD err = GetLastError();
        if (err != ERROR_IO_PENDING) {
            if (lastErrOut) *lastErrOut = err;
            return false;
        }
        ok = GetOverlappedResult(device, &ol, &transferred, TRUE);
        if (!ok) {
            if (lastErrOut) *lastErrOut = GetLastError();
            return false;
        }
    }

    if (lastErrOut) *lastErrOut = ERROR_SUCCESS;
    return true;
}

class ViGEmXbox360 {
public:
    ~ViGEmXbox360() {
        disconnect();
    }

    bool connect(std::string& errorOut) {
        disconnect();

        event_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        if (!event_) {
            errorOut = "CreateEventW failed.";
            return false;
        }

        HDEVINFO devInfo = SetupDiGetClassDevsW(
            &GUID_DEVINTERFACE_BUSENUM_VIGEM,
            nullptr,
            nullptr,
            DIGCF_PRESENT | DIGCF_DEVICEINTERFACE
        );
        if (devInfo == INVALID_HANDLE_VALUE) {
            errorOut = "ViGEmBus interface not found (SetupDiGetClassDevsW failed).";
            disconnect();
            return false;
        }

        SP_DEVICE_INTERFACE_DATA ifData{};
        ifData.cbSize = sizeof(ifData);
        DWORD lastErr = ERROR_FILE_NOT_FOUND;

        for (DWORD idx = 0;
             SetupDiEnumDeviceInterfaces(devInfo, nullptr, &GUID_DEVINTERFACE_BUSENUM_VIGEM, idx, &ifData);
             ++idx) {
            DWORD reqSize = 0;
            SetupDiGetDeviceInterfaceDetailW(devInfo, &ifData, nullptr, 0, &reqSize, nullptr);
            if (reqSize == 0) continue;

            std::vector<BYTE> detailBuf(reqSize);
            auto* detail = reinterpret_cast<PSP_DEVICE_INTERFACE_DETAIL_DATA_W>(detailBuf.data());
            detail->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W);

            if (!SetupDiGetDeviceInterfaceDetailW(devInfo, &ifData, detail, reqSize, &reqSize, nullptr)) {
                lastErr = GetLastError();
                continue;
            }

            HANDLE hBus = CreateFileW(
                detail->DevicePath,
                GENERIC_READ | GENERIC_WRITE,
                FILE_SHARE_READ | FILE_SHARE_WRITE,
                nullptr,
                OPEN_EXISTING,
                FILE_ATTRIBUTE_NORMAL | FILE_FLAG_NO_BUFFERING | FILE_FLAG_WRITE_THROUGH | FILE_FLAG_OVERLAPPED,
                nullptr
            );
            if (hBus == INVALID_HANDLE_VALUE) {
                lastErr = GetLastError();
                continue;
            }

            VIGEM_CHECK_VERSION ver{};
            ver.Size = sizeof(ver);
            ver.Version = VIGEM_COMMON_VERSION;
            if (!syncIoctl(hBus, IOCTL_VIGEM_CHECK_VERSION, &ver, ver.Size, event_, &lastErr)) {
                CloseHandle(hBus);
                continue;
            }

            busHandle_ = hBus;
            break;
        }

        SetupDiDestroyDeviceInfoList(devInfo);

        if (busHandle_ == INVALID_HANDLE_VALUE) {
            errorOut = "Could not open ViGEmBus driver (Win32 error " + std::to_string(lastErr) +
                       "). Make sure ViGEmBus is installed and running.";
            disconnect();
            return false;
        }

        for (ULONG serial = 1; serial <= 128; ++serial) {
            VIGEM_PLUGIN_TARGET plugin{};
            plugin.Size = sizeof(plugin);
            plugin.SerialNo = serial;
            plugin.TargetType = VIGEM_TARGET_XBOX360_WIRED;
            plugin.VendorId = 0x045E;
            plugin.ProductId = 0x028E;

            if (!syncIoctl(busHandle_, IOCTL_VIGEM_PLUGIN_TARGET, &plugin, plugin.Size, event_, &lastErr)) {
                continue;
            }

            VIGEM_WAIT_DEVICE_READY ready{};
            ready.Size = sizeof(ready);
            ready.SerialNo = serial;
            DWORD readyErr = ERROR_SUCCESS;
            if (!syncIoctl(busHandle_, IOCTL_VIGEM_WAIT_DEVICE_READY, &ready, ready.Size, event_, &readyErr)) {
                // Pre-v1.17 ViGEmBus drivers return ERROR_INVALID_PARAMETER for WAIT_DEVICE_READY
                if (readyErr != ERROR_INVALID_PARAMETER) {
                    VIGEM_UNPLUG_TARGET unplug{};
                    unplug.Size = sizeof(unplug);
                    unplug.SerialNo = serial;
                    syncIoctl(busHandle_, IOCTL_VIGEM_UNPLUG_TARGET, &unplug, unplug.Size, event_);
                    lastErr = readyErr;
                    continue;
                }
            }

            serialNo_ = serial;
            pluggedIn_ = true;
            // Allow xusb22.sys a brief moment to queue its first Interrupt IN URB
            Sleep(120);
            return true;
        }

        errorOut = "Failed to plug in virtual Xbox 360 controller on ViGEmBus (Win32 error " +
                   std::to_string(lastErr) + ").";
        disconnect();
        return false;
    }

    bool submitReport(const XUSB_REPORT& report, DWORD* errOut = nullptr, bool blockingRetry = false) {
        if (!pluggedIn_ || busHandle_ == INVALID_HANDLE_VALUE) {
            if (errOut) *errOut = ERROR_INVALID_HANDLE;
            return false;
        }
        XUSB_SUBMIT_REPORT req{};
        req.Size = sizeof(req);
        req.SerialNo = serialNo_;
        req.Report = report;

        DWORD err = ERROR_SUCCESS;
        const int maxAttempts = blockingRetry ? 4 : 1;
        for (int attempt = 0; attempt < maxAttempts; ++attempt) {
            if (syncIoctl(busHandle_, IOCTL_XUSB_SUBMIT_REPORT, &req, req.Size, event_, &err)) {
                if (errOut) *errOut = ERROR_SUCCESS;
                return true;
            }
            if (err != ERROR_NO_MORE_ITEMS) {
                if (errOut) *errOut = err;
                return false;
            }
            if (blockingRetry && (attempt + 1) < maxAttempts) {
                Sleep(1);
            }
        }

        // ERROR_NO_MORE_ITEMS (259) means xusb22.sys has not queued an Interrupt IN URB yet;
        // official ViGEmClient treats this as non-fatal unless ERROR_ACCESS_DENIED occurs.
        if (errOut) *errOut = err;
        return err != ERROR_ACCESS_DENIED;
    }

    void disconnect() {
        if (pluggedIn_ && busHandle_ != INVALID_HANDLE_VALUE) {
            XUSB_REPORT neutral{};
            submitReport(neutral, nullptr, true);

            VIGEM_UNPLUG_TARGET unplug{};
            unplug.Size = sizeof(unplug);
            unplug.SerialNo = serialNo_;
            syncIoctl(busHandle_, IOCTL_VIGEM_UNPLUG_TARGET, &unplug, unplug.Size, event_);
        }
        pluggedIn_ = false;
        serialNo_ = 0;

        if (busHandle_ != INVALID_HANDLE_VALUE) {
            CloseHandle(busHandle_);
            busHandle_ = INVALID_HANDLE_VALUE;
        }
        if (event_) {
            CloseHandle(event_);
            event_ = nullptr;
        }
    }

    ULONG serialNo() const { return serialNo_; }

private:
    HANDLE busHandle_ = INVALID_HANDLE_VALUE;
    HANDLE event_ = nullptr;
    ULONG serialNo_ = 0;
    bool pluggedIn_ = false;
};

struct VkMapping {
    WORD vk = 0;
    bool extended = false;
};

static VkMapping hidUsageToVk(BYTE usage) {
    if (usage >= 0x04 && usage <= 0x1D) {
        return { static_cast<WORD>('A' + (usage - 0x04)), false };
    }
    if (usage >= 0x1E && usage <= 0x26) {
        return { static_cast<WORD>('1' + (usage - 0x1E)), false };
    }
    if (usage >= 0x3A && usage <= 0x45) {
        return { static_cast<WORD>(VK_F1 + (usage - 0x3A)), false };
    }

    switch (usage) {
        case 0x27: return { '0', false };
        case 0x28: return { VK_RETURN, false };
        case 0x29: return { VK_ESCAPE, false };
        case 0x2A: return { VK_BACK, false };
        case 0x2B: return { VK_TAB, false };
        case 0x2C: return { VK_SPACE, false };
        case 0x2D: return { VK_OEM_MINUS, false };
        case 0x2E: return { VK_OEM_PLUS, false };
        case 0x2F: return { VK_OEM_4, false };
        case 0x30: return { VK_OEM_6, false };
        case 0x31: return { VK_OEM_5, false };
        case 0x33: return { VK_OEM_1, false };
        case 0x34: return { VK_OEM_7, false };
        case 0x35: return { VK_OEM_3, false };
        case 0x36: return { VK_OEM_COMMA, false };
        case 0x37: return { VK_OEM_PERIOD, false };
        case 0x38: return { VK_OEM_2, false };
        case 0x39: return { VK_CAPITAL, false };
        case 0x46: return { VK_SNAPSHOT, true };
        case 0x47: return { VK_SCROLL, false };
        case 0x48: return { VK_PAUSE, false };
        case 0x49: return { VK_INSERT, true };
        case 0x4A: return { VK_HOME, true };
        case 0x4B: return { VK_PRIOR, true };
        case 0x4C: return { VK_DELETE, true };
        case 0x4D: return { VK_END, true };
        case 0x4E: return { VK_NEXT, true };
        case 0x4F: return { VK_RIGHT, true };
        case 0x50: return { VK_LEFT, true };
        case 0x51: return { VK_DOWN, true };
        case 0x52: return { VK_UP, true };
        case 0x53: return { VK_NUMLOCK, true };
        default:   return { 0, false };
    }
}

static VkMapping modifierBitToVk(int bit) {
    switch (bit) {
        case 0: return { VK_LCONTROL, false };
        case 1: return { VK_LSHIFT, false };
        case 2: return { VK_LMENU, false };
        case 3: return { VK_LWIN, true };
        case 4: return { VK_RCONTROL, true };
        case 5: return { VK_RSHIFT, false };
        case 6: return { VK_RMENU, true };
        case 7: return { VK_RWIN, true };
        default: return { 0, false };
    }
}

class HostInputInjector {
public:
    ~HostInputInjector() {
        releaseAll();
    }

    void applyMouseReport(const BYTE payload[4]) {
        const BYTE buttons = payload[0];
        const LONG dx = static_cast<LONG>(static_cast<int8_t>(payload[1]));
        const LONG dy = static_cast<LONG>(static_cast<int8_t>(payload[2]));
        const LONG wheel = static_cast<LONG>(static_cast<int8_t>(payload[3]));

        std::vector<INPUT> inputs;
        inputs.reserve(5);

        const BYTE prev = prevMouseButtons_;
        auto pushBtn = [&](BYTE mask, DWORD downFlag, DWORD upFlag) {
            bool wasDown = (prev & mask) != 0;
            bool isDown = (buttons & mask) != 0;
            if (wasDown != isDown) {
                INPUT in{};
                in.type = INPUT_MOUSE;
                in.mi.dwFlags = isDown ? downFlag : upFlag;
                inputs.push_back(in);
            }
        };

        pushBtn(0x01, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP);
        pushBtn(0x02, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP);
        pushBtn(0x04, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP);

        if (dx != 0 || dy != 0) {
            INPUT in{};
            in.type = INPUT_MOUSE;
            in.mi.dx = dx;
            in.mi.dy = dy;
            in.mi.dwFlags = MOUSEEVENTF_MOVE;
            inputs.push_back(in);
        }

        if (wheel != 0) {
            INPUT in{};
            in.type = INPUT_MOUSE;
            in.mi.mouseData = static_cast<DWORD>(wheel * WHEEL_DELTA);
            in.mi.dwFlags = MOUSEEVENTF_WHEEL;
            inputs.push_back(in);
        }

        prevMouseButtons_ = buttons;
        if (!inputs.empty()) {
            SendInput(static_cast<UINT>(inputs.size()), inputs.data(), sizeof(INPUT));
        }
    }

    void applyKeyboardReport(const BYTE payload[8]) {
        const BYTE newMods = payload[0];
        std::array<BYTE, 6> newKeys{};
        std::memcpy(newKeys.data(), &payload[2], 6);

        std::vector<INPUT> inputs;
        inputs.reserve(16);

        auto pushKey = [&](const VkMapping& m, bool isUp) {
            if (m.vk == 0) return;
            INPUT in{};
            in.type = INPUT_KEYBOARD;
            in.ki.wVk = m.vk;
            in.ki.wScan = static_cast<WORD>(MapVirtualKeyW(m.vk, MAPVK_VK_TO_VSC));
            DWORD flags = 0;
            if (m.extended) flags |= KEYEVENTF_EXTENDEDKEY;
            if (isUp) flags |= KEYEVENTF_KEYUP;
            in.ki.dwFlags = flags;
            inputs.push_back(in);
        };

        auto arrayContains = [](const std::array<BYTE, 6>& arr, BYTE val) {
            for (BYTE k : arr) {
                if (k == val) return true;
            }
            return false;
        };

        // Release keys no longer held
        for (BYTE oldKey : prevKeys_) {
            if (oldKey != 0 && !arrayContains(newKeys, oldKey)) {
                pushKey(hidUsageToVk(oldKey), true);
            }
        }

        // Modifier transitions
        for (int bit = 0; bit < 8; ++bit) {
            const BYTE mask = static_cast<BYTE>(1U << bit);
            const bool wasDown = (prevMods_ & mask) != 0;
            const bool isDown = (newMods & mask) != 0;
            if (wasDown != isDown) {
                pushKey(modifierBitToVk(bit), !isDown);
            }
        }

        // Press newly active keys
        for (BYTE newKey : newKeys) {
            if (newKey != 0 && !arrayContains(prevKeys_, newKey)) {
                pushKey(hidUsageToVk(newKey), false);
            }
        }

        prevMods_ = newMods;
        prevKeys_ = newKeys;

        if (!inputs.empty()) {
            SendInput(static_cast<UINT>(inputs.size()), inputs.data(), sizeof(INPUT));
        }
    }

    void releaseAll() {
        const BYTE zeroMouse[4]{};
        const BYTE zeroKbd[8]{};
        applyMouseReport(zeroMouse);
        applyKeyboardReport(zeroKbd);
    }

private:
    BYTE prevMouseButtons_ = 0;
    BYTE prevMods_ = 0;
    std::array<BYTE, 6> prevKeys_{};
};

struct HidGamepadCandidate {
    std::wstring path;
    std::wstring productName;
    std::wstring manufacturer;
    USHORT vendorId = 0;
    USHORT productId = 0;
    USHORT reportByteLength = 0;
    bool isBluetooth = false;
    int score = 0;
};

static std::vector<HidGamepadCandidate> enumerateHidGamepads() {
    std::vector<HidGamepadCandidate> candidates;

    GUID hidGuid{};
    HidD_GetHidGuid(&hidGuid);

    HDEVINFO devInfo = SetupDiGetClassDevsW(
        &hidGuid, nullptr, nullptr, DIGCF_PRESENT | DIGCF_DEVICEINTERFACE
    );
    if (devInfo == INVALID_HANDLE_VALUE) {
        return candidates;
    }

    SP_DEVICE_INTERFACE_DATA ifData{};
    ifData.cbSize = sizeof(ifData);

    for (DWORD idx = 0;
         SetupDiEnumDeviceInterfaces(devInfo, nullptr, &hidGuid, idx, &ifData);
         ++idx) {
        DWORD reqSize = 0;
        SetupDiGetDeviceInterfaceDetailW(devInfo, &ifData, nullptr, 0, &reqSize, nullptr);
        if (reqSize == 0) continue;

        std::vector<BYTE> detailBuf(reqSize);
        auto* detail = reinterpret_cast<PSP_DEVICE_INTERFACE_DETAIL_DATA_W>(detailBuf.data());
        detail->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W);

        if (!SetupDiGetDeviceInterfaceDetailW(devInfo, &ifData, detail, reqSize, &reqSize, nullptr)) {
            continue;
        }

        std::wstring path(detail->DevicePath);
        std::wstring lowerPath = toLowerW(path);
        if (lowerPath.find(L"vigem") != std::wstring::npos ||
            lowerPath.find(L"xusb") != std::wstring::npos) {
            continue;
        }

        HANDLE hDev = CreateFileW(
            path.c_str(),
            GENERIC_READ,
            FILE_SHARE_READ | FILE_SHARE_WRITE,
            nullptr,
            OPEN_EXISTING,
            FILE_FLAG_OVERLAPPED,
            nullptr
        );
        if (hDev == INVALID_HANDLE_VALUE) {
            continue;
        }

        HIDD_ATTRIBUTES attrs{};
        attrs.Size = sizeof(attrs);
        bool hasAttrs = (HidD_GetAttributes(hDev, &attrs) == TRUE);

        // Never attach to ViGEm's virtual Xbox 360 or DS4 controller
        if (hasAttrs &&
            ((attrs.VendorID == 0x045E && attrs.ProductID == 0x028E) ||
             (attrs.VendorID == 0x054C && attrs.ProductID == 0x05C4))) {
            CloseHandle(hDev);
            continue;
        }

        PHIDP_PREPARSED_DATA preparsed = nullptr;
        if (!HidD_GetPreparsedData(hDev, &preparsed) || !preparsed) {
            CloseHandle(hDev);
            continue;
        }

        HIDP_CAPS caps{};
        NTSTATUS capStatus = HidP_GetCaps(preparsed, &caps);
        if (capStatus != HIDP_STATUS_SUCCESS || caps.UsagePage != 0x01 || caps.Usage != 0x05) {
            HidD_FreePreparsedData(preparsed);
            CloseHandle(hDev);
            continue;
        }

        WCHAR prodBuf[128]{};
        WCHAR mfgBuf[128]{};
        HidD_GetProductString(hDev, prodBuf, sizeof(prodBuf));
        HidD_GetManufacturerString(hDev, mfgBuf, sizeof(mfgBuf));

        HidD_FreePreparsedData(preparsed);
        CloseHandle(hDev);

        std::wstring prod(prodBuf);
        std::wstring mfg(mfgBuf);
        std::wstring lowerProd = toLowerW(prod);
        std::wstring lowerMfg = toLowerW(mfg);

        bool isBt = (lowerPath.find(L"00001124") != std::wstring::npos ||
                     lowerPath.find(L"bthenum") != std::wstring::npos);

        int score = 0;
        if (lowerProd.find(L"pocketkeyboard") != std::wstring::npos ||
            lowerMfg.find(L"pocketkeyboard") != std::wstring::npos) {
            score += 100;
        }
        if (isBt || (hasAttrs && attrs.VendorID == 0x1D6B && attrs.ProductID == 0x0104)) {
            score += 50;
        }
        if (caps.InputReportByteLength == 10 || caps.InputReportByteLength == 9) {
            score += 30;
        }
        if (caps.NumberInputButtonCaps >= 1 && caps.NumberInputValueCaps >= 6) {
            score += 20;
        }

        HidGamepadCandidate cand;
        cand.path = std::move(path);
        cand.productName = std::move(prod);
        cand.manufacturer = std::move(mfg);
        cand.vendorId = hasAttrs ? attrs.VendorID : 0;
        cand.productId = hasAttrs ? attrs.ProductID : 0;
        cand.reportByteLength = caps.InputReportByteLength;
        cand.isBluetooth = isBt;
        cand.score = score;
        candidates.push_back(std::move(cand));
    }

    SetupDiDestroyDeviceInfoList(devInfo);

    std::sort(candidates.begin(), candidates.end(), [](const auto& a, const auto& b) {
        return a.score > b.score;
    });
    return candidates;
}

static SHORT scaleStickAxis(ULONG value, bool invertY) {
    int v = static_cast<int>(std::min<ULONG>(value, 255));
    if (!invertY) {
        if (v <= 128) {
            return static_cast<SHORT>(((v - 128) * 32768) / 128);
        }
        return static_cast<SHORT>(((v - 128) * 32767) / 127);
    } else {
        if (v <= 128) {
            return static_cast<SHORT>(((128 - v) * 32767) / 128);
        }
        return static_cast<SHORT>(((128 - v) * 32768) / 127);
    }
}

static void applyButtonOneBased(int buttonIndex, XUSB_REPORT& out, bool& l2Digital, bool& r2Digital) {
    switch (buttonIndex) {
        case 1:  out.wButtons |= XUSB_GAMEPAD_X; break;              // Square / X
        case 2:  out.wButtons |= XUSB_GAMEPAD_A; break;              // Cross / A
        case 3:  out.wButtons |= XUSB_GAMEPAD_B; break;              // Circle / B
        case 4:  out.wButtons |= XUSB_GAMEPAD_Y; break;              // Triangle / Y
        case 5:  out.wButtons |= XUSB_GAMEPAD_LEFT_SHOULDER; break;  // L1 / LB
        case 6:  out.wButtons |= XUSB_GAMEPAD_RIGHT_SHOULDER; break; // R1 / RB
        case 7:  l2Digital = true; break;                            // L2 / LT digital
        case 8:  r2Digital = true; break;                            // R2 / RT digital
        case 9:  out.wButtons |= XUSB_GAMEPAD_BACK; break;           // Create / View
        case 10: out.wButtons |= XUSB_GAMEPAD_START; break;          // Options / Menu
        case 11: out.wButtons |= XUSB_GAMEPAD_LEFT_THUMB; break;     // L3 / LS
        case 12: out.wButtons |= XUSB_GAMEPAD_RIGHT_THUMB; break;    // R3 / RS
        case 13: out.wButtons |= XUSB_GAMEPAD_GUIDE; break;          // PS / Xbox
        case 14: out.wButtons |= XUSB_GAMEPAD_BACK; break;           // Touchpad -> Back
        default: break;
    }
}

static void applyHatValue(ULONG hatVal, XUSB_REPORT& out) {
    switch (hatVal) {
        case 0: out.wButtons |= XUSB_GAMEPAD_DPAD_UP; break;
        case 1: out.wButtons |= XUSB_GAMEPAD_DPAD_UP | XUSB_GAMEPAD_DPAD_RIGHT; break;
        case 2: out.wButtons |= XUSB_GAMEPAD_DPAD_RIGHT; break;
        case 3: out.wButtons |= XUSB_GAMEPAD_DPAD_DOWN | XUSB_GAMEPAD_DPAD_RIGHT; break;
        case 4: out.wButtons |= XUSB_GAMEPAD_DPAD_DOWN; break;
        case 5: out.wButtons |= XUSB_GAMEPAD_DPAD_DOWN | XUSB_GAMEPAD_DPAD_LEFT; break;
        case 6: out.wButtons |= XUSB_GAMEPAD_DPAD_LEFT; break;
        case 7: out.wButtons |= XUSB_GAMEPAD_DPAD_UP | XUSB_GAMEPAD_DPAD_LEFT; break;
        default: break;
    }
}

static XUSB_REPORT parseHidToXusb(
    PHIDP_PREPARSED_DATA preparsed,
    BYTE* report,
    DWORD reportLen
) {
    XUSB_REPORT out{};

    USAGE usages[32]{};
    ULONG usageCount = 32;
    bool l2Digital = false;
    bool r2Digital = false;

    if (HidP_GetUsages(
            HidP_Input,
            0x09,
            0,
            usages,
            &usageCount,
            preparsed,
            reinterpret_cast<PCHAR>(report),
            reportLen
        ) == HIDP_STATUS_SUCCESS) {
        for (ULONG i = 0; i < usageCount; ++i) {
            applyButtonOneBased(static_cast<int>(usages[i]), out, l2Digital, r2Digital);
        }
    }

    ULONG hatVal = 8;
    if (HidP_GetUsageValue(
            HidP_Input,
            0x01,
            0,
            0x39,
            &hatVal,
            preparsed,
            reinterpret_cast<PCHAR>(report),
            reportLen
        ) == HIDP_STATUS_SUCCESS) {
        applyHatValue(hatVal, out);
    }

    ULONG axisVal = 128;
    if (HidP_GetUsageValue(HidP_Input, 0x01, 0, 0x30, &axisVal, preparsed,
                           reinterpret_cast<PCHAR>(report), reportLen) == HIDP_STATUS_SUCCESS) {
        out.sThumbLX = scaleStickAxis(axisVal, false);
    }
    axisVal = 128;
    if (HidP_GetUsageValue(HidP_Input, 0x01, 0, 0x31, &axisVal, preparsed,
                           reinterpret_cast<PCHAR>(report), reportLen) == HIDP_STATUS_SUCCESS) {
        out.sThumbLY = scaleStickAxis(axisVal, true);
    }
    axisVal = 128;
    if (HidP_GetUsageValue(HidP_Input, 0x01, 0, 0x32, &axisVal, preparsed,
                           reinterpret_cast<PCHAR>(report), reportLen) == HIDP_STATUS_SUCCESS) {
        out.sThumbRX = scaleStickAxis(axisVal, false);
    }
    axisVal = 128;
    if (HidP_GetUsageValue(HidP_Input, 0x01, 0, 0x35, &axisVal, preparsed,
                           reinterpret_cast<PCHAR>(report), reportLen) == HIDP_STATUS_SUCCESS) {
        out.sThumbRY = scaleStickAxis(axisVal, true);
    }

    axisVal = 0;
    if (HidP_GetUsageValue(HidP_Input, 0x01, 0, 0x33, &axisVal, preparsed,
                           reinterpret_cast<PCHAR>(report), reportLen) == HIDP_STATUS_SUCCESS) {
        out.bLeftTrigger = static_cast<BYTE>(std::min<ULONG>(axisVal, 255));
    }
    if (l2Digital && out.bLeftTrigger == 0) {
        out.bLeftTrigger = 255;
    }

    axisVal = 0;
    if (HidP_GetUsageValue(HidP_Input, 0x01, 0, 0x34, &axisVal, preparsed,
                           reinterpret_cast<PCHAR>(report), reportLen) == HIDP_STATUS_SUCCESS) {
        out.bRightTrigger = static_cast<BYTE>(std::min<ULONG>(axisVal, 255));
    }
    if (r2Digital && out.bRightTrigger == 0) {
        out.bRightTrigger = 255;
    }

    return out;
}

// Parses raw 9-byte PocketKeyboard gamepad state (same byte layout as HidDescriptors.kt)
static XUSB_REPORT parseRawGamepadToXusb(const BYTE payload[9]) {
    XUSB_REPORT out{};
    const uint16_t buttons = static_cast<uint16_t>(payload[0]) |
                             (static_cast<uint16_t>(payload[1]) << 8);
    bool l2Digital = false;
    bool r2Digital = false;

    for (int bit = 0; bit < 16; ++bit) {
        if ((buttons & (1U << bit)) != 0) {
            applyButtonOneBased(bit + 1, out, l2Digital, r2Digital);
        }
    }

    applyHatValue(static_cast<ULONG>(payload[2] & 0x0F), out);

    out.sThumbLX = scaleStickAxis(payload[3], false);
    out.sThumbLY = scaleStickAxis(payload[4], true);
    out.sThumbRX = scaleStickAxis(payload[5], false);
    out.sThumbRY = scaleStickAxis(payload[6], true);

    out.bLeftTrigger = payload[7];
    if (l2Digital && out.bLeftTrigger == 0) {
        out.bLeftTrigger = 255;
    }

    out.bRightTrigger = payload[8];
    if (r2Digital && out.bRightTrigger == 0) {
        out.bRightTrigger = 255;
    }

    return out;
}

static bool reportsEqual(const XUSB_REPORT& a, const XUSB_REPORT& b) {
    return std::memcmp(&a, &b, sizeof(XUSB_REPORT)) == 0;
}

struct GamepadCoalescer {
    XUSB_REPORT latest{};
    XUSB_REPORT lastDelivered{};
    bool hasLatest = false;
    bool hasDelivered = false;
    bool dirty = false;

    void update(const XUSB_REPORT& next, ViGEmXbox360& vigem) {
        bool buttonsChanged = hasLatest && (next.wButtons != latest.wButtons);
        latest = next;
        hasLatest = true;
        dirty = !hasDelivered || !reportsEqual(latest, lastDelivered);
        if (buttonsChanged) {
            flush(vigem);
        }
    }

    void flush(ViGEmXbox360& vigem) {
        if (!dirty || !hasLatest) return;
        DWORD err = ERROR_SUCCESS;
        if (vigem.submitReport(latest, &err, false) && err == ERROR_SUCCESS) {
            lastDelivered = latest;
            hasDelivered = true;
            dirty = false;
        }
    }
};

static bool validateV2Frame(const BYTE* data, size_t avail, size_t& frameSizeOut) {
    if (avail < 5) return false;
    if (data[0] != V2_HEADER_0 || data[1] != V2_HEADER_1) return false;
    const BYTE len = data[3];
    if (len > 16) return false;
    const size_t total = 5 + static_cast<size_t>(len);
    if (avail < total) return false;

    BYTE xorSum = static_cast<BYTE>(data[2] ^ len);
    for (size_t i = 0; i < len; ++i) {
        xorSum ^= data[4 + i];
    }
    if (xorSum != data[4 + len]) return false;

    frameSizeOut = total;
    return true;
}

static void dispatchV2Payload(
    BYTE type,
    const BYTE* payload,
    BYTE len,
    ViGEmXbox360& vigem,
    HostInputInjector& injector,
    GamepadCoalescer& coalescer,
    bool& sawPongOut
) {
    switch (type) {
        case V2_TYPE_GAMEPAD:
            if (len >= 9) {
                coalescer.update(parseRawGamepadToXusb(payload), vigem);
            }
            break;

        case V2_TYPE_MOUSE:
            if (len >= 4) {
                injector.applyMouseReport(payload);
            }
            break;

        case V2_TYPE_KEYBOARD:
            if (len >= 8) {
                injector.applyKeyboardReport(payload);
            }
            break;

        case V2_TYPE_PONG:
            sawPongOut = true;
            break;

        default:
            break;
    }
}

struct UsbTetherGateway {
    std::string gatewayIp;
    std::wstring adapterName;
};

static std::vector<UsbTetherGateway> discoverUsbTetherGateways() {
    std::vector<UsbTetherGateway> result;

    ULONG bufLen = 16384;
    std::vector<BYTE> buf(bufLen);
    ULONG ret = GetAdaptersAddresses(
        AF_INET,
        GAA_FLAG_INCLUDE_GATEWAYS,
        nullptr,
        reinterpret_cast<PIP_ADAPTER_ADDRESSES>(buf.data()),
        &bufLen
    );
    if (ret == ERROR_BUFFER_OVERFLOW) {
        buf.resize(bufLen);
        ret = GetAdaptersAddresses(
            AF_INET,
            GAA_FLAG_INCLUDE_GATEWAYS,
            nullptr,
            reinterpret_cast<PIP_ADAPTER_ADDRESSES>(buf.data()),
            &bufLen
        );
    }
    if (ret != NO_ERROR) {
        return result;
    }

    for (PIP_ADAPTER_ADDRESSES aa = reinterpret_cast<PIP_ADAPTER_ADDRESSES>(buf.data());
         aa != nullptr;
         aa = aa->Next) {
        if (aa->OperStatus != IfOperStatusUp || aa->IfType == IF_TYPE_SOFTWARE_LOOPBACK) {
            continue;
        }

        std::wstring friendly = aa->FriendlyName ? std::wstring(aa->FriendlyName) : L"";
        std::wstring desc = aa->Description ? std::wstring(aa->Description) : L"";
        std::wstring combinedLower = toLowerW(friendly + L" " + desc);

        const bool looksUsb =
            combinedLower.find(L"rndis") != std::wstring::npos ||
            combinedLower.find(L"remote ndis") != std::wstring::npos ||
            combinedLower.find(L"usb") != std::wstring::npos ||
            combinedLower.find(L"android") != std::wstring::npos ||
            combinedLower.find(L"ncm") != std::wstring::npos ||
            combinedLower.find(L"cdc") != std::wstring::npos ||
            combinedLower.find(L"tether") != std::wstring::npos;
        if (!looksUsb) {
            continue;
        }

        for (PIP_ADAPTER_GATEWAY_ADDRESS_LH gw = aa->FirstGatewayAddress;
             gw != nullptr;
             gw = gw->Next) {
            if (!gw->Address.lpSockaddr || gw->Address.lpSockaddr->sa_family != AF_INET) {
                continue;
            }
            const auto* sin = reinterpret_cast<const sockaddr_in*>(gw->Address.lpSockaddr);
            char ipBuf[INET_ADDRSTRLEN]{};
            if (inet_ntop(AF_INET, &sin->sin_addr, ipBuf, sizeof(ipBuf))) {
                std::string ip(ipBuf);
                if (!ip.empty() && ip != "0.0.0.0") {
                    UsbTetherGateway entry;
                    entry.gatewayIp = std::move(ip);
                    entry.adapterName = !friendly.empty() ? friendly : desc;
                    result.push_back(std::move(entry));
                    break;
                }
            }
        }
    }

    return result;
}

static SOCKET tryConnectUdpBridge(const std::string& ipStr, DWORD timeoutMs) {
    sockaddr_in peer{};
    peer.sin_family = AF_INET;
    peer.sin_port = htons(POCKETBRIDGE_PORT);
    if (inet_pton(AF_INET, ipStr.c_str(), &peer.sin_addr) != 1) {
        return INVALID_SOCKET;
    }

    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock == INVALID_SOCKET) {
        return INVALID_SOCKET;
    }

    if (connect(sock, reinterpret_cast<const sockaddr*>(&peer), sizeof(peer)) != 0) {
        closesocket(sock);
        return INVALID_SOCKET;
    }

    u_long nonBlocking = 1;
    ioctlsocket(sock, FIONBIO, &nonBlocking);

    if (send(sock, reinterpret_cast<const char*>(V2_PING_FRAME), sizeof(V2_PING_FRAME), 0) == SOCKET_ERROR) {
        closesocket(sock);
        return INVALID_SOCKET;
    }

    const ULONGLONG deadline = GetTickCount64() + timeoutMs;
    BYTE tmp[128]{};
    while (g_running.load()) {
        ULONGLONG now = GetTickCount64();
        if (now >= deadline) break;

        DWORD remain = static_cast<DWORD>(deadline - now);
        DWORD slice = std::min<DWORD>(remain, 50);

        fd_set readSet;
        FD_ZERO(&readSet);
        FD_SET(sock, &readSet);

        timeval tv{};
        tv.tv_sec = static_cast<long>(slice / 1000);
        tv.tv_usec = static_cast<long>((slice % 1000) * 1000);

        int sel = select(0, &readSet, nullptr, nullptr, &tv);
        if (sel <= 0) {
            if (sel == SOCKET_ERROR) break;
            continue;
        }

        int n = recv(sock, reinterpret_cast<char*>(tmp), sizeof(tmp), 0);
        if (n <= 0) {
            break;
        }
        size_t fsize = 0;
        if (validateV2Frame(tmp, static_cast<size_t>(n), fsize) && tmp[2] == V2_TYPE_PONG) {
            return sock;
        }
    }

    closesocket(sock);
    return INVALID_SOCKET;
}

static void runUdpBridgeSession(
    SOCKET sock,
    const std::string& peerIp,
    const std::wstring& label,
    bool& waitingMessagePrinted
) {
    ViGEmXbox360 vigem;
    std::string err;
    if (!vigem.connect(err)) {
        std::fprintf(stderr, "[ERROR] %s\n", err.c_str());
        closesocket(sock);
        for (int i = 0; i < 20 && g_running.load(); ++i) {
            Sleep(100);
        }
        return;
    }

    waitingMessagePrinted = false;
    std::printf(
        "[Connected] %ls (UDP -> %s:%u) -> Virtual Xbox 360 #%lu + Mouse/Keys ACTIVE!\n",
        label.c_str(),
        peerIp.c_str(),
        static_cast<unsigned>(POCKETBRIDGE_PORT),
        vigem.serialNo()
    );
    std::fflush(stdout);

    HostInputInjector injector;
    GamepadCoalescer coalescer;

    ULONGLONG lastActivityMs = GetTickCount64();
    ULONGLONG lastPingMs = lastActivityMs;
    BYTE recvBuf[512]{};
    std::string disconnectReason = "stopped";

    while (g_running.load()) {
        ULONGLONG now = GetTickCount64();
        if (now - lastPingMs >= 400) {
            if (send(sock, reinterpret_cast<const char*>(V2_PING_FRAME), sizeof(V2_PING_FRAME), 0) == SOCKET_ERROR) {
                int sErr = WSAGetLastError();
                if (sErr != WSAEWOULDBLOCK) {
                    disconnectReason = "send error " + std::to_string(sErr);
                    break;
                }
            }
            lastPingMs = now;
        }

        fd_set readSet;
        FD_ZERO(&readSet);
        FD_SET(sock, &readSet);

        timeval tv{};
        tv.tv_sec = 0;
        tv.tv_usec = coalescer.dirty ? 2000 : 50000;

        int sel = select(0, &readSet, nullptr, nullptr, &tv);
        if (sel == SOCKET_ERROR) {
            disconnectReason = "select error " + std::to_string(WSAGetLastError());
            break;
        }

        if (sel > 0) {
            bool fatalErr = false;
            while (true) {
                int n = recv(sock, reinterpret_cast<char*>(recvBuf), sizeof(recvBuf), 0);
                if (n < 0) {
                    int sErr = WSAGetLastError();
                    if (sErr != WSAEWOULDBLOCK) {
                        disconnectReason = "recv error " + std::to_string(sErr);
                        fatalErr = true;
                    }
                    break;
                }
                if (n == 0) {
                    disconnectReason = "EOF";
                    fatalErr = true;
                    break;
                }

                size_t offset = 0;
                const size_t totalBytes = static_cast<size_t>(n);
                while (totalBytes - offset >= 5) {
                    size_t fsize = 0;
                    if (!validateV2Frame(recvBuf + offset, totalBytes - offset, fsize)) {
                        ++offset;
                        continue;
                    }
                    bool sawPong = false;
                    dispatchV2Payload(
                        recvBuf[offset + 2],
                        recvBuf + offset + 4,
                        recvBuf[offset + 3],
                        vigem,
                        injector,
                        coalescer,
                        sawPong
                    );
                    lastActivityMs = GetTickCount64();
                    offset += fsize;
                }
            }
            if (fatalErr) break;
        }

        coalescer.flush(vigem);

        if (GetTickCount64() - lastActivityMs > 2500) {
            disconnectReason = "keepalive timeout";
            break;
        }
    }

    injector.releaseAll();
    closesocket(sock);
    vigem.disconnect();

    if (g_running.load()) {
        std::printf("[Disconnected] UDP USB Bridge ended (%s). Waiting for reconnect...\n", disconnectReason.c_str());
        std::fflush(stdout);
        waitingMessagePrinted = true;
    }
}

static bool fileExistsW(const std::wstring& path) {
    DWORD attr = GetFileAttributesW(path.c_str());
    return attr != INVALID_FILE_ATTRIBUTES && (attr & FILE_ATTRIBUTE_DIRECTORY) == 0;
}

static std::wstring findAdbExecutable() {
    wchar_t exePath[MAX_PATH]{};
    DWORD exeLen = GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    if (exeLen > 0 && exeLen < MAX_PATH) {
        std::wstring dir(exePath);
        size_t pos = dir.find_last_of(L"\\/");
        if (pos != std::wstring::npos) {
            std::wstring candidate = dir.substr(0, pos + 1) + L"adb.exe";
            if (fileExistsW(candidate)) return candidate;
        }
    }

    wchar_t foundBuf[MAX_PATH]{};
    if (SearchPathW(nullptr, L"adb.exe", nullptr, MAX_PATH, foundBuf, nullptr) > 0) {
        return std::wstring(foundBuf);
    }

    const wchar_t* envVars[] = { L"LOCALAPPDATA", L"ANDROID_HOME", L"ANDROID_SDK_ROOT" };
    const wchar_t* suffixes[] = {
        L"\\Android\\Sdk\\platform-tools\\adb.exe",
        L"\\platform-tools\\adb.exe",
        L"\\platform-tools\\adb.exe"
    };

    for (size_t i = 0; i < 3; ++i) {
        wchar_t val[MAX_PATH]{};
        DWORD len = GetEnvironmentVariableW(envVars[i], val, MAX_PATH);
        if (len > 0 && len < MAX_PATH) {
            std::wstring candidate = std::wstring(val) + suffixes[i];
            if (fileExistsW(candidate)) return candidate;
        }
    }

    return L"";
}

static bool runAdbForward(const std::wstring& adbPath) {
    if (adbPath.empty()) return false;

    std::wstring cmd =
        L"\"" + adbPath + L"\" forward tcp:" + std::to_wstring(POCKETBRIDGE_PORT) +
        L" tcp:" + std::to_wstring(POCKETBRIDGE_PORT);

    std::vector<wchar_t> cmdMutable(cmd.begin(), cmd.end());
    cmdMutable.push_back(L'\0');

    STARTUPINFOW si{};
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;

    PROCESS_INFORMATION pi{};
    BOOL ok = CreateProcessW(
        nullptr,
        cmdMutable.data(),
        nullptr,
        nullptr,
        FALSE,
        CREATE_NO_WINDOW,
        nullptr,
        nullptr,
        &si,
        &pi
    );
    if (!ok) {
        return false;
    }

    DWORD waitRes = WaitForSingleObject(pi.hProcess, 2500);
    DWORD exitCode = 1;
    if (waitRes == WAIT_OBJECT_0) {
        GetExitCodeProcess(pi.hProcess, &exitCode);
    } else {
        TerminateProcess(pi.hProcess, 1);
    }

    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return exitCode == 0;
}

static SOCKET tryConnectTcpOnce(
    const std::string& ipStr,
    DWORD connectTimeoutMs,
    std::vector<BYTE>& initialStreamOut
) {
    initialStreamOut.clear();

    sockaddr_in peer{};
    peer.sin_family = AF_INET;
    peer.sin_port = htons(POCKETBRIDGE_PORT);
    if (inet_pton(AF_INET, ipStr.c_str(), &peer.sin_addr) != 1) {
        return INVALID_SOCKET;
    }

    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        return INVALID_SOCKET;
    }

    BOOL noDelay = TRUE;
    setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&noDelay), sizeof(noDelay));

    u_long nonBlocking = 1;
    ioctlsocket(sock, FIONBIO, &nonBlocking);

    int res = connect(sock, reinterpret_cast<const sockaddr*>(&peer), sizeof(peer));
    if (res == SOCKET_ERROR) {
        int err = WSAGetLastError();
        if (err != WSAEWOULDBLOCK) {
            closesocket(sock);
            return INVALID_SOCKET;
        }

        const ULONGLONG deadline = GetTickCount64() + connectTimeoutMs;
        bool connected = false;
        while (g_running.load()) {
            ULONGLONG now = GetTickCount64();
            if (now >= deadline) break;
            DWORD slice = static_cast<DWORD>(std::min<ULONGLONG>(deadline - now, 80));

            fd_set writeSet;
            fd_set exceptSet;
            FD_ZERO(&writeSet);
            FD_ZERO(&exceptSet);
            FD_SET(sock, &writeSet);
            FD_SET(sock, &exceptSet);

            timeval tv{};
            tv.tv_sec = static_cast<long>(slice / 1000);
            tv.tv_usec = static_cast<long>((slice % 1000) * 1000);

            int sel = select(0, nullptr, &writeSet, &exceptSet, &tv);
            if (sel > 0) {
                int soErr = 0;
                int optLen = sizeof(soErr);
                if (getsockopt(sock, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&soErr), &optLen) == 0 &&
                    FD_ISSET(sock, &writeSet) && soErr == 0) {
                    connected = true;
                }
                break;
            }
            if (sel == SOCKET_ERROR) break;
        }
        if (!connected) {
            closesocket(sock);
            return INVALID_SOCKET;
        }
    }

    // Require genuine v2 PING -> PONG handshake (ADB accepts TCP before verifying phone listener!)
    if (send(sock, reinterpret_cast<const char*>(V2_PING_FRAME), sizeof(V2_PING_FRAME), 0) == SOCKET_ERROR) {
        closesocket(sock);
        return INVALID_SOCKET;
    }

    const ULONGLONG pongDeadline = GetTickCount64() + 350;
    BYTE tmp[128]{};
    bool sawPong = false;

    while (g_running.load() && !sawPong) {
        ULONGLONG now = GetTickCount64();
        if (now >= pongDeadline) break;
        DWORD slice = static_cast<DWORD>(std::min<ULONGLONG>(pongDeadline - now, 60));

        fd_set readSet;
        FD_ZERO(&readSet);
        FD_SET(sock, &readSet);

        timeval tv{};
        tv.tv_sec = static_cast<long>(slice / 1000);
        tv.tv_usec = static_cast<long>((slice % 1000) * 1000);

        int sel = select(0, &readSet, nullptr, nullptr, &tv);
        if (sel <= 0) {
            if (sel == SOCKET_ERROR) break;
            continue;
        }

        int n = recv(sock, reinterpret_cast<char*>(tmp), sizeof(tmp), 0);
        if (n <= 0) {
            closesocket(sock);
            return INVALID_SOCKET;
        }
        initialStreamOut.insert(initialStreamOut.end(), tmp, tmp + n);

        size_t pos = 0;
        while (initialStreamOut.size() - pos >= 5) {
            size_t fsize = 0;
            if (!validateV2Frame(initialStreamOut.data() + pos, initialStreamOut.size() - pos, fsize)) {
                ++pos;
                continue;
            }
            if (initialStreamOut[pos + 2] == V2_TYPE_PONG) {
                sawPong = true;
                initialStreamOut.erase(initialStreamOut.begin(), initialStreamOut.begin() + pos + fsize);
                break;
            }
            pos += fsize;
        }
    }

    if (!sawPong) {
        closesocket(sock);
        return INVALID_SOCKET;
    }

    return sock;
}

static SOCKET tryConnectAdbTcp(
    const std::wstring& adbPath,
    ULONGLONG& lastAdbAttemptMs,
    std::vector<BYTE>& initialStreamOut
) {
    SOCKET sock = tryConnectTcpOnce("127.0.0.1", 150, initialStreamOut);
    if (sock != INVALID_SOCKET) {
        return sock;
    }

    const ULONGLONG now = GetTickCount64();
    if (!adbPath.empty() && (now - lastAdbAttemptMs) >= 2000) {
        lastAdbAttemptMs = now;
        if (runAdbForward(adbPath)) {
            sock = tryConnectTcpOnce("127.0.0.1", 250, initialStreamOut);
            if (sock != INVALID_SOCKET) {
                return sock;
            }
        }
    }

    return INVALID_SOCKET;
}

static void runTcpBridgeSession(
    SOCKET sock,
    std::vector<BYTE> streamBuf,
    bool& waitingMessagePrinted
) {
    ViGEmXbox360 vigem;
    std::string err;
    if (!vigem.connect(err)) {
        std::fprintf(stderr, "[ERROR] %s\n", err.c_str());
        closesocket(sock);
        for (int i = 0; i < 20 && g_running.load(); ++i) {
            Sleep(100);
        }
        return;
    }

    waitingMessagePrinted = false;
    std::printf(
        "[Connected] USB Cable via ADB (TCP_NODELAY) -> Virtual Xbox 360 #%lu + Mouse/Keys ACTIVE!\n",
        vigem.serialNo()
    );
    std::fflush(stdout);

    HostInputInjector injector;
    GamepadCoalescer coalescer;

    auto drainStream = [&]() {
        while (streamBuf.size() >= 5) {
            if (streamBuf[0] != V2_HEADER_0 || streamBuf[1] != V2_HEADER_1) {
                auto it = std::find(streamBuf.begin() + 1, streamBuf.end(), V2_HEADER_0);
                streamBuf.erase(streamBuf.begin(), it);
                continue;
            }
            const BYTE len = streamBuf[3];
            if (len > 16) {
                streamBuf.erase(streamBuf.begin());
                continue;
            }
            const size_t total = 5 + static_cast<size_t>(len);
            if (streamBuf.size() < total) {
                break;
            }
            size_t validatedSize = 0;
            if (!validateV2Frame(streamBuf.data(), streamBuf.size(), validatedSize)) {
                streamBuf.erase(streamBuf.begin());
                continue;
            }
            bool sawPong = false;
            dispatchV2Payload(
                streamBuf[2],
                streamBuf.data() + 4,
                len,
                vigem,
                injector,
                coalescer,
                sawPong
            );
            streamBuf.erase(streamBuf.begin(), streamBuf.begin() + validatedSize);
        }
    };

    drainStream();
    coalescer.flush(vigem);

    ULONGLONG lastActivityMs = GetTickCount64();
    ULONGLONG lastPingMs = lastActivityMs;
    BYTE recvTmp[512]{};
    std::string disconnectReason = "stopped";

    while (g_running.load()) {
        ULONGLONG now = GetTickCount64();
        if (now - lastPingMs >= 500) {
            if (send(sock, reinterpret_cast<const char*>(V2_PING_FRAME), sizeof(V2_PING_FRAME), 0) == SOCKET_ERROR) {
                int sErr = WSAGetLastError();
                if (sErr != WSAEWOULDBLOCK) {
                    disconnectReason = "send error " + std::to_string(sErr);
                    break;
                }
            }
            lastPingMs = now;
        }

        fd_set readSet;
        FD_ZERO(&readSet);
        FD_SET(sock, &readSet);

        timeval tv{};
        tv.tv_sec = 0;
        tv.tv_usec = coalescer.dirty ? 2000 : 50000;

        int sel = select(0, &readSet, nullptr, nullptr, &tv);
        if (sel == SOCKET_ERROR) {
            disconnectReason = "select error " + std::to_string(WSAGetLastError());
            break;
        }

        if (sel > 0) {
            bool fatalErr = false;
            while (true) {
                int n = recv(sock, reinterpret_cast<char*>(recvTmp), sizeof(recvTmp), 0);
                if (n < 0) {
                    int sErr = WSAGetLastError();
                    if (sErr != WSAEWOULDBLOCK) {
                        disconnectReason = "recv error " + std::to_string(sErr);
                        fatalErr = true;
                    }
                    break;
                }
                if (n == 0) {
                    disconnectReason = "EOF";
                    fatalErr = true;
                    break;
                }
                streamBuf.insert(streamBuf.end(), recvTmp, recvTmp + n);
                lastActivityMs = GetTickCount64();
            }
            drainStream();
            if (fatalErr) break;
        }

        coalescer.flush(vigem);

        if (GetTickCount64() - lastActivityMs > 3000) {
            disconnectReason = "keepalive timeout";
            break;
        }
    }

    injector.releaseAll();
    closesocket(sock);
    vigem.disconnect();

    if (g_running.load()) {
        std::printf("[Disconnected] ADB USB Bridge closed (%s). Waiting for reconnect...\n", disconnectReason.c_str());
        std::fflush(stdout);
        waitingMessagePrinted = true;
    }
}

struct StaleBtHidCacheInfo {
    std::string macAddress;
    std::string deviceName;
    DWORD sdpRecordBytes = 0;
};

static std::vector<StaleBtHidCacheInfo> detectStaleBluetoothHidCaches() {
    std::vector<StaleBtHidCacheInfo> results;
    static const BYTE kPocketKbdName[] = {
        'P','o','c','k','e','t','K','e','y','b','o','a','r','d'
    };
    static const BYTE kGamepadUsageHeader[] = { 0x05, 0x01, 0x09, 0x05 };

    HKEY hDevices = nullptr;
    if (RegOpenKeyExW(
            HKEY_LOCAL_MACHINE,
            L"SYSTEM\\CurrentControlSet\\Services\\BTHPORT\\Parameters\\Devices",
            0,
            KEY_READ,
            &hDevices
        ) != ERROR_SUCCESS) {
        return results;
    }

    wchar_t subkeyName[128]{};
    DWORD subkeyLen = 128;
    for (DWORD i = 0;
         RegEnumKeyExW(hDevices, i, subkeyName, &subkeyLen, nullptr, nullptr, nullptr, nullptr) == ERROR_SUCCESS;
         ++i, subkeyLen = 128) {
        HKEY hDev = nullptr;
        if (RegOpenKeyExW(hDevices, subkeyName, 0, KEY_READ, &hDev) != ERROR_SUCCESS) {
            continue;
        }

        std::string devName;
        BYTE nameBuf[256]{};
        DWORD nameType = 0;
        DWORD nameSize = sizeof(nameBuf);
        if (RegQueryValueExW(hDev, L"Name", nullptr, &nameType, nameBuf, &nameSize) == ERROR_SUCCESS &&
            nameSize > 0) {
            size_t len = 0;
            while (len < nameSize && nameBuf[len] != 0) ++len;
            devName.assign(reinterpret_cast<const char*>(nameBuf), len);
        }

        HKEY hCached = nullptr;
        if (RegOpenKeyExW(hDev, L"CachedServices", 0, KEY_READ, &hCached) == ERROR_SUCCESS) {
            wchar_t valName[128]{};
            DWORD valNameLen = 128;
            DWORD valType = 0;
            DWORD valSize = 0;

            for (DWORD j = 0;
                 RegEnumValueW(hCached, j, valName, &valNameLen, nullptr, &valType, nullptr, &valSize) == ERROR_SUCCESS;
                 ++j, valNameLen = 128, valSize = 0) {
                if (valType == REG_BINARY && valSize > 0 && valSize <= 8192) {
                    std::vector<BYTE> sdpBuf(valSize);
                    DWORD readSize = valSize;
                    if (RegQueryValueExW(hCached, valName, nullptr, nullptr, sdpBuf.data(), &readSize) == ERROR_SUCCESS) {
                        sdpBuf.resize(readSize);
                        if (containsBytes(sdpBuf, kPocketKbdName, sizeof(kPocketKbdName)) &&
                            !containsBytes(sdpBuf, kGamepadUsageHeader, sizeof(kGamepadUsageHeader))) {
                            StaleBtHidCacheInfo info;
                            info.macAddress = formatMacString(subkeyName);
                            info.deviceName = devName.empty() ? info.macAddress : devName;
                            info.sdpRecordBytes = readSize;
                            results.push_back(std::move(info));
                            break;
                        }
                    }
                }
            }
            RegCloseKey(hCached);
        }

        RegCloseKey(hDev);
    }

    RegCloseKey(hDevices);
    return results;
}

struct BluetoothBridgeCandidate {
    BTH_ADDR address = 0;
    std::wstring name;
    ULONG classOfDevice = 0;
    bool isConnected = false;
    bool isPhone = false;
};

static std::vector<BluetoothBridgeCandidate> enumerateBluetoothBridgeDevices() {
    std::vector<BluetoothBridgeCandidate> candidates;

    BLUETOOTH_DEVICE_SEARCH_PARAMS params{};
    params.dwSize = sizeof(params);
    params.fReturnAuthenticated = TRUE;
    params.fReturnRemembered = TRUE;
    params.fReturnConnected = TRUE;
    params.fReturnUnknown = FALSE;
    params.fIssueInquiry = FALSE;
    params.cTimeoutMultiplier = 0;

    BLUETOOTH_DEVICE_INFO info{};
    info.dwSize = sizeof(info);

    HBLUETOOTH_DEVICE_FIND hFind = BluetoothFindFirstDevice(&params, &info);
    if (!hFind) {
        return candidates;
    }

    do {
        const ULONG majorClass = (info.ulClassofDevice & 0x1F00);
        // Skip Audio/Video devices (earbuds, headphones, speakers)
        if ((info.fAuthenticated || info.fConnected) &&
            info.Address.ullLong != 0 &&
            majorClass != 0x0400) {
            BluetoothBridgeCandidate cand;
            cand.address = info.Address.ullLong;
            cand.name = info.szName[0] ? std::wstring(info.szName) : L"(Bluetooth Device)";
            cand.classOfDevice = info.ulClassofDevice;
            cand.isConnected = (info.fConnected == TRUE);
            cand.isPhone = (majorClass == 0x0200);
            candidates.push_back(std::move(cand));
        }
        std::memset(&info, 0, sizeof(info));
        info.dwSize = sizeof(info);
    } while (BluetoothFindNextDevice(hFind, &info));

    BluetoothFindDeviceClose(hFind);

    std::sort(candidates.begin(), candidates.end(), [](const auto& a, const auto& b) {
        if (a.isConnected != b.isConnected) return a.isConnected > b.isConnected;
        if (a.isPhone != b.isPhone) return a.isPhone > b.isPhone;
        return a.name < b.name;
    });
    return candidates;
}

static SOCKET tryConnectBluetoothRfcomm(BTH_ADDR addr, DWORD timeoutMs) {
    SOCKET sock = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
    if (sock == INVALID_SOCKET) {
        return INVALID_SOCKET;
    }

    u_long nonBlocking = 1;
    if (ioctlsocket(sock, FIONBIO, &nonBlocking) != 0) {
        closesocket(sock);
        return INVALID_SOCKET;
    }

    SOCKADDR_BTH sa{};
    sa.addressFamily = AF_BTH;
    sa.btAddr = addr;
    sa.serviceClassId = GUID_POCKETPAD_RFCOMM_BRIDGE;
    sa.port = BT_PORT_ANY;

    int res = connect(sock, reinterpret_cast<SOCKADDR*>(&sa), sizeof(sa));
    if (res == 0) {
        nonBlocking = 0;
        ioctlsocket(sock, FIONBIO, &nonBlocking);
        return sock;
    }

    int err = WSAGetLastError();
    if (err != WSAEWOULDBLOCK) {
        closesocket(sock);
        return INVALID_SOCKET;
    }

    const ULONGLONG deadline = GetTickCount64() + timeoutMs;
    while (g_running.load()) {
        ULONGLONG now = GetTickCount64();
        if (now >= deadline) {
            break;
        }
        DWORD remaining = static_cast<DWORD>(deadline - now);
        DWORD sliceMs = std::min<DWORD>(remaining, 150);

        fd_set writeSet;
        fd_set exceptSet;
        FD_ZERO(&writeSet);
        FD_ZERO(&exceptSet);
        FD_SET(sock, &writeSet);
        FD_SET(sock, &exceptSet);

        timeval tv{};
        tv.tv_sec = static_cast<long>(sliceMs / 1000);
        tv.tv_usec = static_cast<long>((sliceMs % 1000) * 1000);

        int sel = select(0, nullptr, &writeSet, &exceptSet, &tv);
        if (sel > 0) {
            int soErr = 0;
            int optLen = sizeof(soErr);
            if (getsockopt(sock, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&soErr), &optLen) != 0) {
                soErr = WSAECONNREFUSED;
            }
            if (FD_ISSET(sock, &writeSet) && soErr == 0) {
                nonBlocking = 0;
                ioctlsocket(sock, FIONBIO, &nonBlocking);
                return sock;
            }
            closesocket(sock);
            return INVALID_SOCKET;
        }
        if (sel == SOCKET_ERROR) {
            closesocket(sock);
            return INVALID_SOCKET;
        }
    }

    closesocket(sock);
    return INVALID_SOCKET;
}

static void runHidSession(const HidGamepadCandidate& target, bool& waitingMessagePrinted) {
    HANDLE hidHandle = CreateFileW(
        target.path.c_str(),
        GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        nullptr,
        OPEN_EXISTING,
        FILE_FLAG_OVERLAPPED,
        nullptr
    );
    if (hidHandle == INVALID_HANDLE_VALUE) {
        Sleep(400);
        return;
    }

    HidD_SetNumInputBuffers(hidHandle, 64);

    PHIDP_PREPARSED_DATA preparsed = nullptr;
    HIDP_CAPS caps{};
    if (!HidD_GetPreparsedData(hidHandle, &preparsed) || !preparsed ||
        HidP_GetCaps(preparsed, &caps) != HIDP_STATUS_SUCCESS) {
        if (preparsed) HidD_FreePreparsedData(preparsed);
        CloseHandle(hidHandle);
        Sleep(400);
        return;
    }

    ViGEmXbox360 vigem;
    std::string err;
    if (!vigem.connect(err)) {
        std::fprintf(stderr, "[ERROR] %s\n", err.c_str());
        HidD_FreePreparsedData(preparsed);
        CloseHandle(hidHandle);
        for (int i = 0; i < 20 && g_running.load(); ++i) {
            Sleep(100);
        }
        return;
    }

    waitingMessagePrinted = false;
    std::printf(
        "[Connected] %s HID \"%ls\" (VID=%04X PID=%04X) -> Virtual Xbox 360 Controller #%lu ACTIVE!\n",
        target.isBluetooth ? "Bluetooth" : "USB",
        target.productName.empty() ? L"Gamepad" : target.productName.c_str(),
        target.vendorId,
        target.productId,
        vigem.serialNo()
    );
    std::fflush(stdout);

    HANDLE readEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    std::vector<BYTE> reportBuf(caps.InputReportByteLength);
    XUSB_REPORT lastReport{};
    bool hasLastReport = false;

    while (g_running.load()) {
        OVERLAPPED ol{};
        ol.hEvent = readEvent;
        ResetEvent(readEvent);

        DWORD bytesRead = 0;
        BOOL ok = ReadFile(
            hidHandle,
            reportBuf.data(),
            static_cast<DWORD>(reportBuf.size()),
            &bytesRead,
            &ol
        );

        if (!ok) {
            DWORD readErr = GetLastError();
            if (readErr != ERROR_IO_PENDING) {
                break;
            }

            bool completed = false;
            while (g_running.load()) {
                DWORD waitRes = WaitForSingleObject(readEvent, 250);
                if (waitRes == WAIT_OBJECT_0) {
                    completed = (GetOverlappedResult(hidHandle, &ol, &bytesRead, FALSE) == TRUE);
                    break;
                }
                if (waitRes != WAIT_TIMEOUT) {
                    completed = false;
                    break;
                }
            }

            if (!g_running.load()) {
                CancelIoEx(hidHandle, &ol);
                break;
            }
            if (!completed) {
                break;
            }
        }

        if (bytesRead > 0) {
            XUSB_REPORT xusb = parseHidToXusb(preparsed, reportBuf.data(), bytesRead);
            if (!hasLastReport || !reportsEqual(xusb, lastReport)) {
                DWORD submitErr = ERROR_SUCCESS;
                if (vigem.submitReport(xusb, &submitErr, false) && submitErr == ERROR_SUCCESS) {
                    lastReport = xusb;
                    hasLastReport = true;
                }
            }
        }
    }

    if (readEvent) CloseHandle(readEvent);
    HidD_FreePreparsedData(preparsed);
    CloseHandle(hidHandle);
    vigem.disconnect();

    if (g_running.load()) {
        std::printf("[Disconnected] HID gamepad disconnected. Waiting for reconnect...\n");
        std::fflush(stdout);
        waitingMessagePrinted = true;
    }
}

static void runRfcommBridgeSession(
    SOCKET sock,
    const BluetoothBridgeCandidate& dev,
    bool& waitingMessagePrinted
) {
    ViGEmXbox360 vigem;
    std::string err;
    if (!vigem.connect(err)) {
        std::fprintf(stderr, "[ERROR] %s\n", err.c_str());
        closesocket(sock);
        for (int i = 0; i < 20 && g_running.load(); ++i) {
            Sleep(100);
        }
        return;
    }

    waitingMessagePrinted = false;
    std::printf(
        "[Connected] Bluetooth Direct Bridge -> \"%ls\" (%s) -> Virtual Xbox 360 Controller #%lu ACTIVE!\n",
        dev.name.c_str(),
        formatBthAddr(dev.address).c_str(),
        vigem.serialNo()
    );
    std::fflush(stdout);

    std::vector<BYTE> streamBuf;
    streamBuf.reserve(256);
    BYTE recvTmp[256]{};
    GamepadCoalescer coalescer;

    while (g_running.load()) {
        fd_set readSet;
        FD_ZERO(&readSet);
        FD_SET(sock, &readSet);

        timeval tv{};
        tv.tv_sec = 0;
        tv.tv_usec = coalescer.dirty ? 2000 : 50000;

        int sel = select(0, &readSet, nullptr, nullptr, &tv);
        if (sel == SOCKET_ERROR) {
            break;
        }
        if (sel == 0) {
            coalescer.flush(vigem);
            continue;
        }

        int n = recv(sock, reinterpret_cast<char*>(recvTmp), sizeof(recvTmp), 0);
        if (n <= 0) {
            break;
        }
        streamBuf.insert(streamBuf.end(), recvTmp, recvTmp + n);

        while (streamBuf.size() >= BT_BRIDGE_FRAME_SIZE) {
            if (streamBuf[0] != BT_BRIDGE_HEADER_0 || streamBuf[1] != BT_BRIDGE_HEADER_1) {
                auto it = std::find(streamBuf.begin() + 1, streamBuf.end(), BT_BRIDGE_HEADER_0);
                streamBuf.erase(streamBuf.begin(), it);
                continue;
            }

            BYTE checksum = 0;
            for (size_t i = 2; i <= 10; ++i) {
                checksum ^= streamBuf[i];
            }
            if (checksum != streamBuf[11]) {
                streamBuf.erase(streamBuf.begin());
                continue;
            }

            coalescer.update(parseRawGamepadToXusb(&streamBuf[2]), vigem);
            streamBuf.erase(streamBuf.begin(), streamBuf.begin() + BT_BRIDGE_FRAME_SIZE);
        }

        coalescer.flush(vigem);
    }

    closesocket(sock);
    vigem.disconnect();

    if (g_running.load()) {
        std::printf("[Disconnected] Bluetooth Direct Bridge closed. Waiting for reconnect...\n");
        std::fflush(stdout);
        waitingMessagePrinted = true;
    }
}

static int runSelfTest() {
    // 1. Validate v2 protocol checksum + gamepad parser + HID-to-VK keymap
    BYTE gpFrame[14]{};
    gpFrame[0] = V2_HEADER_0;
    gpFrame[1] = V2_HEADER_1;
    gpFrame[2] = V2_TYPE_GAMEPAD;
    gpFrame[3] = 9;
    gpFrame[4] = 0x02; // Cross / A
    gpFrame[5] = 0x00;
    gpFrame[6] = 0x00; // DPad Up
    gpFrame[7] = 128;
    gpFrame[8] = 0;    // Stick full up -> sThumbLY == +32767
    gpFrame[9] = 128;
    gpFrame[10] = 128;
    gpFrame[11] = 200; // LT
    gpFrame[12] = 0;
    BYTE xorSum = gpFrame[2] ^ gpFrame[3];
    for (size_t i = 0; i < 9; ++i) xorSum ^= gpFrame[4 + i];
    gpFrame[13] = xorSum;

    size_t parsedSize = 0;
    if (!validateV2Frame(gpFrame, sizeof(gpFrame), parsedSize) || parsedSize != 14) {
        std::fprintf(stderr, "[ERROR] v2 frame validation self-test failed.\n");
        return 1;
    }
    XUSB_REPORT parsedGp = parseRawGamepadToXusb(&gpFrame[4]);
    if ((parsedGp.wButtons & (XUSB_GAMEPAD_A | XUSB_GAMEPAD_DPAD_UP)) != (XUSB_GAMEPAD_A | XUSB_GAMEPAD_DPAD_UP) ||
        parsedGp.sThumbLY != 32767 || parsedGp.bLeftTrigger != 200) {
        std::fprintf(stderr, "[ERROR] v2 gamepad decode self-test failed.\n");
        return 1;
    }
    if (hidUsageToVk(0x04).vk != 'A' || hidUsageToVk(0x4F).vk != VK_RIGHT || !hidUsageToVk(0x4F).extended) {
        std::fprintf(stderr, "[ERROR] HID keymap self-test failed.\n");
        return 1;
    }
    std::printf("[OK] PocketBridge v2 frame, gamepad parser & HID keymap verified.\n");

    // 2. Test ViGEmBus driver plug/submit/unplug
    std::printf("[PocketPadXboxBridge] Testing ViGEmBus connection...\n");
    ViGEmXbox360 vigem;
    std::string err;
    if (!vigem.connect(err)) {
        std::fprintf(stderr, "[ERROR] %s\n", err.c_str());
        return 1;
    }
    std::printf("[OK] Virtual Xbox 360 Controller plugged in (Serial #%lu).\n", vigem.serialNo());

    XUSB_REPORT rep{};
    DWORD repErr = 0;
    if (!vigem.submitReport(rep, &repErr, true)) {
        std::fprintf(stderr, "[ERROR] Failed to submit neutral XUSB report (Win32 error %lu).\n", repErr);
        return 1;
    }
    rep.wButtons = XUSB_GAMEPAD_A;
    if (!vigem.submitReport(rep, &repErr, true)) {
        std::fprintf(stderr, "[ERROR] Failed to submit button XUSB report (Win32 error %lu).\n", repErr);
        return 1;
    }
    std::printf("[OK] Report submit status: Win32=%lu (%s).\n",
                repErr,
                repErr == ERROR_SUCCESS ? "delivered to xusb22.sys" : "queued / non-fatal");
    rep.wButtons = 0;
    vigem.submitReport(rep, nullptr, true);
    vigem.disconnect();
    std::printf("[OK] Unplugged cleanly. ViGEmBus XInput bridge is ready.\n");
    return 0;
}

static int listGamepads() {
    auto tethers = discoverUsbTetherGateways();
    std::printf("USB Tethering UDP candidates (%zu):\n", tethers.size());
    for (size_t i = 0; i < tethers.size(); ++i) {
        std::printf("  [%zu] %s (%ls)\n", i, tethers[i].gatewayIp.c_str(), tethers[i].adapterName.c_str());
    }

    std::wstring adbPath = findAdbExecutable();
    std::printf("\nADB executable: %ls\n", adbPath.empty() ? L"(not found in PATH or Android SDK)" : adbPath.c_str());

    auto list = enumerateHidGamepads();
    std::printf("\nDetected HID Gamepad devices (%zu):\n", list.size());
    for (size_t i = 0; i < list.size(); ++i) {
        const auto& c = list[i];
        std::printf(
            "  [%zu] Type=%s Score=%d VID=%04X PID=%04X ReportLen=%u Product=\"%ls\" Mfg=\"%ls\"\n",
            i,
            c.isBluetooth ? "Bluetooth-HID" : "USB-HID",
            c.score,
            c.vendorId,
            c.productId,
            c.reportByteLength,
            c.productName.empty() ? L"(none)" : c.productName.c_str(),
            c.manufacturer.empty() ? L"(none)" : c.manufacturer.c_str()
        );
    }

    auto btDevs = enumerateBluetoothBridgeDevices();
    std::printf("\nPaired Bluetooth Direct Bridge candidates (%zu):\n", btDevs.size());
    for (size_t i = 0; i < btDevs.size(); ++i) {
        const auto& d = btDevs[i];
        std::printf(
            "  [%zu] %s Name=\"%ls\" Connected=%s Phone=%s\n",
            i,
            formatBthAddr(d.address).c_str(),
            d.name.c_str(),
            d.isConnected ? "yes" : "no",
            d.isPhone ? "yes" : "no"
        );
    }

    auto staleCaches = detectStaleBluetoothHidCaches();
    if (!staleCaches.empty()) {
        std::printf("\n[Notice] Stale Windows Bluetooth HID SDP Cache detected:\n");
        for (const auto& s : staleCaches) {
            std::printf(
                "  - \"%s\" (%s): cached %lu-byte Keyboard+Mouse descriptor (missing Gamepad Col03).\n"
                "    USB Bridge or Bluetooth Direct Bridge bypasses this automatically.\n",
                s.deviceName.c_str(),
                s.macAddress.c_str(),
                s.sdpRecordBytes
            );
        }
    }

    return 0;
}

int main(int argc, char** argv) {
    ScopedTimerResolution timerRes(1);
    WSADATA wsaData{};
    bool wsaInitialized = (WSAStartup(MAKEWORD(2, 2), &wsaData) == 0);

    std::string manualUdpIp;
    for (int i = 1; i < argc; ++i) {
        std::string arg(argv[i]);
        if (arg == "--test") {
            int rc = runSelfTest();
            if (wsaInitialized) WSACleanup();
            return rc;
        }
        if (arg == "--list") {
            int rc = listGamepads();
            if (wsaInitialized) WSACleanup();
            return rc;
        }
        if (arg == "--udp" && (i + 1) < argc) {
            manualUdpIp = argv[++i];
        }
    }

    SetConsoleCtrlHandler(consoleCtrlHandler, TRUE);

    std::wstring adbPath = findAdbExecutable();

    std::printf("==========================================================================\n");
    std::printf("  PocketKeyboard -> Xbox 360 (XInput) + Mouse/Keyboard Bridge (ViGEmBus)\n");
    std::printf("  No-Root USB Priority: [1] USB Tethering UDP  [2] USB ADB TCP_NODELAY\n");
    std::printf("  Fallbacks:            [3] Native HID         [4] Bluetooth Direct\n");
    if (!manualUdpIp.empty()) {
        std::printf("  Manual UDP Target:    %s:%u\n", manualUdpIp.c_str(), static_cast<unsigned>(POCKETBRIDGE_PORT));
    }
    if (!adbPath.empty()) {
        std::printf("  ADB Auto-Tunnel:      %ls\n", adbPath.c_str());
    }
    std::printf("  Keep this window open while playing (Ctrl+C to quit).\n");
    std::printf("==========================================================================\n\n");

    auto staleCaches = detectStaleBluetoothHidCaches();
    for (const auto& s : staleCaches) {
        std::printf(
            "[Note] \"%s\" (%s) has an older Windows BT HID cache (%lu B).\n"
            "       USB Bridge & Bluetooth Direct Bridge bypass this automatically!\n\n",
            s.deviceName.c_str(),
            s.macAddress.c_str(),
            s.sdpRecordBytes
        );
    }

    bool waitingMessagePrinted = false;
    int probeCycle = 0;
    ULONGLONG lastAdbForwardAttemptMs = 0;

    while (g_running.load()) {
        // 1. Lowest latency (~0.5-1.5ms): USB Tethering / manual UDP
        if (wsaInitialized) {
            if (!manualUdpIp.empty()) {
                SOCKET udpSock = tryConnectUdpBridge(manualUdpIp, 200);
                if (udpSock != INVALID_SOCKET) {
                    runUdpBridgeSession(udpSock, manualUdpIp, L"Manual UDP", waitingMessagePrinted);
                    probeCycle = 0;
                    continue;
                }
            }

            auto tethers = discoverUsbTetherGateways();
            bool connectedUdp = false;
            for (const auto& gw : tethers) {
                if (!g_running.load()) break;
                SOCKET udpSock = tryConnectUdpBridge(gw.gatewayIp, 180);
                if (udpSock != INVALID_SOCKET) {
                    runUdpBridgeSession(
                        udpSock,
                        gw.gatewayIp,
                        L"USB Tethering (" + gw.adapterName + L")",
                        waitingMessagePrinted
                    );
                    connectedUdp = true;
                    probeCycle = 0;
                    break;
                }
            }
            if (connectedUdp) {
                continue;
            }

            // 2. USB Cable via ADB TCP_NODELAY (~2-4ms, leaves PC Wi-Fi untouched)
            std::vector<BYTE> initialTcpStream;
            SOCKET tcpSock = tryConnectAdbTcp(adbPath, lastAdbForwardAttemptMs, initialTcpStream);
            if (tcpSock != INVALID_SOCKET) {
                runTcpBridgeSession(tcpSock, std::move(initialTcpStream), waitingMessagePrinted);
                probeCycle = 0;
                continue;
            }
        }

        // 3. Native HID Gamepad (USB Root hidg2 or freshly paired Bluetooth Col03)
        auto hidCandidates = enumerateHidGamepads();
        if (!hidCandidates.empty()) {
            runHidSession(hidCandidates.front(), waitingMessagePrinted);
            probeCycle = 0;
            continue;
        }

        // 4. Bluetooth Direct RFCOMM Gamepad Bridge (Wireless no-root)
        if (wsaInitialized) {
            auto btDevs = enumerateBluetoothBridgeDevices();
            bool connectedViaBtBridge = false;
            const bool probeDisconnectedPhones = ((probeCycle % 6) == 0);

            for (const auto& dev : btDevs) {
                if (!g_running.load()) break;
                if (!dev.isConnected && !(probeDisconnectedPhones && dev.isPhone)) {
                    continue;
                }

                SOCKET sock = tryConnectBluetoothRfcomm(dev.address, dev.isConnected ? 800 : 1400);
                if (sock != INVALID_SOCKET) {
                    runRfcommBridgeSession(sock, dev, waitingMessagePrinted);
                    connectedViaBtBridge = true;
                    probeCycle = 0;
                    break;
                }
            }

            if (connectedViaBtBridge) {
                continue;
            }
        }

        if (!waitingMessagePrinted) {
            std::printf(
                "[Waiting] Open PocketKeyboard on your phone in:\n"
                "          - USB Bridge mode (USB Tethering ~1ms OR USB Debugging/ADB ~2-4ms)\n"
                "          - OR Bluetooth mode\n"
            );
            std::fflush(stdout);
            waitingMessagePrinted = true;
        }

        ++probeCycle;
        for (int i = 0; i < 6 && g_running.load(); ++i) {
            Sleep(100);
        }
    }

    if (wsaInitialized) {
        WSACleanup();
    }
    std::printf("\n[Stopped] Virtual Xbox 360 Controller unplugged cleanly.\n");
    return 0;
}
