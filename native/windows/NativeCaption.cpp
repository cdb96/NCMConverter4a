#include <jni.h>
#include <windows.h>
#include <windowsx.h>
#include <dwmapi.h>
#include <cstdint>
#include <cwchar>

namespace {

COLORREF toColorRef(jint argb) {
    return RGB((argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff);
}

constexpr wchar_t kTitleBarProperty[] = L"NCMConverter4a.CustomTitleBar";
constexpr wchar_t kCanvasProperty[] = L"NCMConverter4a.TitleBarCanvas";

struct TitleBarState {
    WNDPROC original;
    int height;
    int buttonWidth;
};

struct CanvasState {
    HWND parent;
    WNDPROC original;
};

LRESULT CALLBACK canvasWindowProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    auto* state = static_cast<CanvasState*>(GetPropW(window, kCanvasProperty));
    if (state == nullptr) return DefWindowProcW(window, message, wParam, lParam);
    if (message == WM_NCHITTEST) {
        const LRESULT parentHit = SendMessageW(state->parent, WM_NCHITTEST, wParam, lParam);
        if (parentHit != HTCLIENT) return HTTRANSPARENT;
    }
    if (message == WM_NCDESTROY) {
        WNDPROC original = state->original;
        RemovePropW(window, kCanvasProperty);
        SetWindowLongPtrW(window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(original));
        delete state;
        return CallWindowProcW(original, window, message, wParam, lParam);
    }
    return CallWindowProcW(state->original, window, message, wParam, lParam);
}

BOOL CALLBACK installCanvasHitTesting(HWND child, LPARAM parentValue) {
    wchar_t className[64]{};
    GetClassNameW(child, className, 64);
    if (std::wcscmp(className, L"SunAwtCanvas") != 0 ||
        GetPropW(child, kCanvasProperty) != nullptr) return TRUE;

    auto* state = new CanvasState{reinterpret_cast<HWND>(parentValue), nullptr};
    if (!SetPropW(child, kCanvasProperty, state)) {
        delete state;
        return TRUE;
    }
    SetLastError(0);
    state->original = reinterpret_cast<WNDPROC>(
        SetWindowLongPtrW(child, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(canvasWindowProc)));
    if (state->original == nullptr) {
        RemovePropW(child, kCanvasProperty);
        delete state;
    }
    return TRUE;
}

LRESULT CALLBACK titleBarWindowProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    auto* state = static_cast<TitleBarState*>(GetPropW(window, kTitleBarProperty));
    if (state == nullptr) return DefWindowProcW(window, message, wParam, lParam);

    if (message == WM_NCCALCSIZE) {
        if (wParam && IsZoomed(window)) {
            auto* params = reinterpret_cast<NCCALCSIZE_PARAMS*>(lParam);
            MONITORINFO monitorInfo{sizeof(MONITORINFO)};
            if (GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &monitorInfo)) {
                params->rgrc[0] = monitorInfo.rcWork;
            }
        }
        return 0;
    }

    if (message == WM_NCHITTEST) {
        RECT rect{};
        GetWindowRect(window, &rect);
        const int x = GET_X_LPARAM(lParam) - rect.left;
        const int y = GET_Y_LPARAM(lParam) - rect.top;
        if (!IsZoomed(window)) {
            const UINT dpi = GetDpiForWindow(window);
            const int border = GetSystemMetricsForDpi(SM_CXFRAME, dpi) +
                               GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
            const bool left = x < border;
            const bool right = x >= rect.right - rect.left - border;
            const bool top = y < border;
            const bool bottom = y >= rect.bottom - rect.top - border;
            if (top && left) return HTTOPLEFT;
            if (top && right) return HTTOPRIGHT;
            if (bottom && left) return HTBOTTOMLEFT;
            if (bottom && right) return HTBOTTOMRIGHT;
            if (left) return HTLEFT;
            if (right) return HTRIGHT;
            if (top) return HTTOP;
            if (bottom) return HTBOTTOM;
        }
        if (y >= 0 && y < state->height && x >= 0 &&
            x < rect.right - rect.left - state->buttonWidth) return HTCAPTION;
        return HTCLIENT;
    }

    if (message == WM_PARENTNOTIFY && LOWORD(wParam) == WM_CREATE) {
        installCanvasHitTesting(reinterpret_cast<HWND>(lParam), reinterpret_cast<LPARAM>(window));
    }

    if (message == WM_SETCURSOR && LOWORD(lParam) == HTCAPTION) {
        SetCursor(LoadCursorW(nullptr, MAKEINTRESOURCEW(32512)));
        return TRUE;
    }
    if (message == WM_NCLBUTTONDBLCLK && wParam == HTCAPTION) {
        ShowWindow(window, IsZoomed(window) ? SW_RESTORE : SW_MAXIMIZE);
        return 0;
    }
    if (message == WM_NCLBUTTONDOWN &&
        (wParam == HTCAPTION || wParam == HTLEFT || wParam == HTRIGHT ||
         wParam == HTTOP || wParam == HTBOTTOM || wParam == HTTOPLEFT ||
         wParam == HTTOPRIGHT || wParam == HTBOTTOMLEFT || wParam == HTBOTTOMRIGHT)) {
        return DefWindowProcW(window, message, wParam, lParam);
    }
    if (message == WM_NCDESTROY) {
        WNDPROC original = state->original;
        RemovePropW(window, kTitleBarProperty);
        SetWindowLongPtrW(window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(original));
        delete state;
        return CallWindowProcW(original, window, message, wParam, lParam);
    }
    return CallWindowProcW(state->original, window, message, wParam, lParam);
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cdb96_ncmconverter4a_NativeCaption_setColors(
    JNIEnv*, jobject, jlong windowHandle, jint captionArgb, jint textArgb, jboolean dark) {
    HWND window = reinterpret_cast<HWND>(static_cast<std::uintptr_t>(windowHandle));
    if (window == nullptr || !IsWindow(window)) return JNI_FALSE;

    const COLORREF caption = toColorRef(captionArgb);
    const COLORREF text = toColorRef(textArgb);
    const BOOL darkMode = dark == JNI_TRUE;
    DwmSetWindowAttribute(window, DWMWA_USE_IMMERSIVE_DARK_MODE, &darkMode, sizeof(darkMode));
    const HRESULT captionResult = DwmSetWindowAttribute(window, DWMWA_CAPTION_COLOR, &caption, sizeof(caption));
    const HRESULT textResult = DwmSetWindowAttribute(window, DWMWA_TEXT_COLOR, &text, sizeof(text));
    return SUCCEEDED(captionResult) && SUCCEEDED(textResult) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cdb96_ncmconverter4a_NativeCaption_installTallTitleBar(
    JNIEnv*, jobject, jlong windowHandle, jint height, jint buttonWidth) {
    HWND window = reinterpret_cast<HWND>(static_cast<std::uintptr_t>(windowHandle));
    if (window == nullptr || !IsWindow(window) || height <= 0 || buttonWidth <= 0) return JNI_FALSE;

    auto* existing = static_cast<TitleBarState*>(GetPropW(window, kTitleBarProperty));
    if (existing != nullptr) {
        existing->height = height;
        existing->buttonWidth = buttonWidth;
        return JNI_TRUE;
    }

    auto* state = new TitleBarState{nullptr, height, buttonWidth};
    if (!SetPropW(window, kTitleBarProperty, state)) {
        delete state;
        return JNI_FALSE;
    }
    SetLastError(0);
    const auto original = reinterpret_cast<WNDPROC>(
        SetWindowLongPtrW(window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(titleBarWindowProc)));
    if (original == nullptr) {
        RemovePropW(window, kTitleBarProperty);
        delete state;
        return JNI_FALSE;
    }
    state->original = original;
    const DWM_WINDOW_CORNER_PREFERENCE corners = DWMWCP_ROUND;
    DwmSetWindowAttribute(window, DWMWA_WINDOW_CORNER_PREFERENCE, &corners, sizeof(corners));
    SetWindowPos(window, nullptr, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
    EnumChildWindows(window, installCanvasHitTesting, reinterpret_cast<LPARAM>(window));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cdb96_ncmconverter4a_NativeCaption_isMaximized(
    JNIEnv*, jobject, jlong windowHandle) {
    HWND window = reinterpret_cast<HWND>(static_cast<std::uintptr_t>(windowHandle));
    return window != nullptr && IsWindow(window) && IsZoomed(window) ? JNI_TRUE : JNI_FALSE;
}
