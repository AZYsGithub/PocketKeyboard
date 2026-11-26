#define WIN32_LEAN_AND_MEAN
#define NOMINMAX

#include <windows.h>
#include <setupapi.h>
#include <initguid.h>
#include <winioctl.h>

extern "C" {
#include <hidsdi.h>
#include <hidpi.h>
}

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
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

        // Find free slot and plug in virtual Xbox 360 controller
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
            return true;
        }

        errorOut = "Failed to plug in virtual Xbox 360 controller on ViGEmBus (Win32 error " +
                   std::to_string(lastErr) + ").";
        disconnect();
        return false;
    }

    bool submitReport(const XUSB_REPORT& report, DWORD* errOut = nullptr) {
        if (!pluggedIn_ || busHandle_ == INVALID_HANDLE_VALUE) {
            if (errOut) *errOut = ERROR_INVALID_HANDLE;
            return false;
        }
        XUSB_SUBMIT_REPORT req{};
        req.Size = sizeof(req);
        req.SerialNo = serialNo_;
        req.Report = report;

        DWORD err = ERROR_SUCCESS;
        for (int attempt = 0; attempt < 4; ++attempt) {
            if (syncIoctl(busHandle_, IOCTL_XUSB_SUBMIT_REPORT, &req, req.Size, event_, &err)) {
                if (errOut) *errOut = ERROR_SUCCESS;
                return true;
            }
            if (err != ERROR_NO_MORE_ITEMS) {
                if (errOut) *errOut = err;
                return false;
            }
            Sleep(1);
        }

        // ERROR_NO_MORE_ITEMS (259) means xusb22.sys has not queued an Interrupt IN URB yet;
        // official ViGEmClient treats this as non-fatal unless ERROR_ACCESS_DENIED occurs.
        if (errOut) *errOut = err;
        return err != ERROR_ACCESS_DENIED;
    }

    void disconnect() {
        if (pluggedIn_ && busHandle_ != INVALID_HANDLE_VALUE) {
            XUSB_REPORT neutral{};
            submitReport(neutral);

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

struct HidGamepadCandidate {
    std::wstring path;
    std::wstring productName;
    std::wstring manufacturer;
    USHORT vendorId = 0;
    USHORT productId = 0;
    USHORT reportByteLength = 0;
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

        int score = 0;
        if (lowerProd.find(L"pocketkeyboard") != std::wstring::npos ||
            lowerMfg.find(L"pocketkeyboard") != std::wstring::npos) {
            score += 100;
        }
        if (lowerPath.find(L"bthenum") != std::wstring::npos ||
            (hasAttrs && attrs.VendorID == 0x1D6B && attrs.ProductID == 0x0104)) {
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
            switch (usages[i]) {
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

static int runSelfTest() {
    std::printf("[PocketPadXboxBridge] Testing ViGEmBus connection...\n");
    ViGEmXbox360 vigem;
    std::string err;
    if (!vigem.connect(err)) {
        std::fprintf(stderr, "[ERROR] %s\n", err.c_str());
        return 1;
    }
    std::printf("[OK] Virtual Xbox 360 Controller plugged in (Serial #%lu).\n", vigem.serialNo());
    Sleep(200);

    XUSB_REPORT rep{};
    DWORD repErr = 0;
    if (!vigem.submitReport(rep, &repErr)) {
        std::fprintf(stderr, "[ERROR] Failed to submit neutral XUSB report (Win32 error %lu).\n", repErr);
        return 1;
    }
    rep.wButtons = XUSB_GAMEPAD_A;
    if (!vigem.submitReport(rep, &repErr)) {
        std::fprintf(stderr, "[ERROR] Failed to submit button XUSB report (Win32 error %lu).\n", repErr);
        return 1;
    }
    std::printf("[OK] Report submit status: Win32=%lu (%s).\n",
                repErr,
                repErr == ERROR_SUCCESS ? "delivered to xusb22.sys" : "queued / non-fatal");
    rep.wButtons = 0;
    vigem.submitReport(rep);
    vigem.disconnect();
    std::printf("[OK] Unplugged cleanly. ViGEmBus XInput bridge is ready.\n");
    return 0;
}

static int listGamepads() {
    auto list = enumerateHidGamepads();
    if (list.empty()) {
        std::printf("No non-ViGEm HID Gamepad devices currently connected.\n");
        return 0;
    }
    std::printf("Detected HID Gamepad devices (%zu):\n", list.size());
    for (size_t i = 0; i < list.size(); ++i) {
        const auto& c = list[i];
        std::printf(
            "  [%zu] Score=%d VID=%04X PID=%04X ReportLen=%u Product=\"%ls\" Mfg=\"%ls\"\n",
            i,
            c.score,
            c.vendorId,
            c.productId,
            c.reportByteLength,
            c.productName.empty() ? L"(none)" : c.productName.c_str(),
            c.manufacturer.empty() ? L"(none)" : c.manufacturer.c_str()
        );
    }
    return 0;
}

static bool reportsEqual(const XUSB_REPORT& a, const XUSB_REPORT& b) {
    return std::memcmp(&a, &b, sizeof(XUSB_REPORT)) == 0;
}

int main(int argc, char** argv) {
    if (argc > 1) {
        std::string arg(argv[1]);
        if (arg == "--test") {
            return runSelfTest();
        }
        if (arg == "--list") {
            return listGamepads();
        }
    }

    SetConsoleCtrlHandler(consoleCtrlHandler, TRUE);

    std::printf("============================================================\n");
    std::printf("  PocketKeyboard -> Xbox 360 (XInput) Bridge via ViGEmBus\n");
    std::printf("  Keep this window open while playing PC games.\n");
    std::printf("  Press Ctrl+C to stop and unplug the virtual controller.\n");
    std::printf("============================================================\n\n");

    bool waitingMessagePrinted = false;

    while (g_running.load()) {
        auto candidates = enumerateHidGamepads();
        if (candidates.empty()) {
            if (!waitingMessagePrinted) {
                std::printf("[Waiting] Connect your phone via Bluetooth (or USB) to PocketKeyboard...\n");
                std::fflush(stdout);
                waitingMessagePrinted = true;
            }
            for (int i = 0; i < 10 && g_running.load(); ++i) {
                Sleep(100);
            }
            continue;
        }

        const auto& target = candidates.front();
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
            Sleep(500);
            continue;
        }

        HidD_SetNumInputBuffers(hidHandle, 64);

        PHIDP_PREPARSED_DATA preparsed = nullptr;
        HIDP_CAPS caps{};
        if (!HidD_GetPreparsedData(hidHandle, &preparsed) || !preparsed ||
            HidP_GetCaps(preparsed, &caps) != HIDP_STATUS_SUCCESS) {
            if (preparsed) HidD_FreePreparsedData(preparsed);
            CloseHandle(hidHandle);
            Sleep(500);
            continue;
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
            continue;
        }

        waitingMessagePrinted = false;
        std::printf(
            "[Connected] Reading \"%ls\" (VID=%04X PID=%04X) -> Virtual Xbox 360 Controller #%lu ACTIVE!\n",
            target.productName.empty() ? L"Bluetooth HID Gamepad" : target.productName.c_str(),
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
                    if (vigem.submitReport(xusb, &submitErr) && submitErr == ERROR_SUCCESS) {
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
            std::printf("[Disconnected] Phone gamepad disconnected. Waiting for reconnect...\n");
            std::fflush(stdout);
            waitingMessagePrinted = true;
        }
    }

    std::printf("\n[Stopped] Virtual Xbox 360 Controller unplugged cleanly.\n");
    return 0;
}
