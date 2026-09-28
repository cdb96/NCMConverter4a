#include <jni.h>
#include <windows.h>
#include <dwmapi.h>
#include <cstdint>

namespace {

COLORREF toColorRef(jint argb) {
    return RGB((argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff);
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
