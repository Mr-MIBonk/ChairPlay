// The app's web UI inside the main window: a WebView2 child of an existing window (the GLFW one), through the
// webview library (https://github.com/webview/webview), with the few calls the library has no C API for: bounds,
// visibility and focus. The host's own message loop (GLFW's) pumps WebView2's messages, so every call here belongs
// on the thread that owns the parent window.
//
// The functions take and return UTF-16 strings (JNA's WString) and report failures as text, so the Kotlin side
// needs no knowledge of COM.

#include "webview/webview.h"

#include <cstring>
#include <cwchar>
#include <string>

#define CPW_API extern "C" __declspec(dllexport)

namespace {

// Declared in WebView2.h; the IID constants themselves are not, without MSVC's __uuidof.
constexpr IID IID_Controller2{0xc979903e, 0xd4ca, 0x4228, {0x92, 0xeb, 0x47, 0xee, 0x3f, 0xa9, 0x6e, 0xab}};
constexpr IID IID_Settings3{0xfdb5ab74, 0xaf33, 0x4854, {0x84, 0xf0, 0x0a, 0x63, 0x1d, 0xeb, 0x5e, 0xba}};

struct host {
    webview::webview *view;
    HWND widget;
    ICoreWebView2Controller *controller;
    bool com;
};

void copy_error(const std::string &message, char *error, int capacity) {
    if (!error || capacity <= 0) return;
    auto length = message.size() < static_cast<size_t>(capacity - 1) ? message.size() : static_cast<size_t>(capacity - 1);
    std::memcpy(error, message.data(), length);
    error[length] = '\0';
}

}  // namespace

// Creates the web view as a hidden child of [parent] and returns its handle, or null with the reason in [error].
// [user_data] is the folder WebView2 keeps its profile in; the official loader honours the variable over the
// library's own choice (%APPDATA%\<exe name>). A missing WebView2 runtime fails here.
CPW_API void *cpw_create(void *parent, const wchar_t *user_data, int debug, char *error, int capacity) {
    if (user_data && *user_data) SetEnvironmentVariableW(L"WEBVIEW2_USER_DATA_FOLDER", user_data);
    // The library leaves COM to the host when it embeds; WebView2 wants a single-threaded apartment.
    HRESULT init = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (init == RPC_E_CHANGED_MODE) {
        copy_error("this thread already joined a multithreaded COM apartment", error, capacity);
        return nullptr;
    }
    auto *h = new host{nullptr, nullptr, nullptr, SUCCEEDED(init)};
    try {
        h->view = new webview::webview(debug != 0, parent);
        h->widget = static_cast<HWND>(h->view->widget().value());
        h->controller = static_cast<ICoreWebView2Controller *>(h->view->browser_controller().value());
    } catch (const std::exception &e) {
        copy_error(e.what(), error, capacity);
        delete h->view;
        if (h->com) CoUninitialize();
        delete h;
        return nullptr;
    }
    // Black like the window behind it, until the page paints.
    ICoreWebView2Controller2 *controller2 = nullptr;
    if (SUCCEEDED(h->controller->QueryInterface(IID_Controller2, reinterpret_cast<void **>(&controller2)))) {
        controller2->put_DefaultBackgroundColor(COREWEBVIEW2_COLOR{255, 0, 0, 0});
        controller2->Release();
    }
    // An app screen, not a browser: no reload, find, print or zoom keys, and no context menu outside debugging.
    ICoreWebView2 *core = nullptr;
    if (SUCCEEDED(h->controller->get_CoreWebView2(&core))) {
        ICoreWebView2Settings *settings = nullptr;
        if (SUCCEEDED(core->get_Settings(&settings))) {
            settings->put_AreDefaultContextMenusEnabled(debug ? TRUE : FALSE);
            settings->put_IsZoomControlEnabled(FALSE);
            ICoreWebView2Settings3 *settings3 = nullptr;
            if (SUCCEEDED(settings->QueryInterface(IID_Settings3, reinterpret_cast<void **>(&settings3)))) {
                settings3->put_AreBrowserAcceleratorKeysEnabled(debug ? TRUE : FALSE);
                settings3->Release();
            }
            settings->Release();
        }
        core->Release();
    }
    ShowWindow(h->widget, SW_HIDE);
    h->controller->put_IsVisible(FALSE);
    return h;
}

CPW_API void cpw_navigate(void *handle, const wchar_t *url) {
    auto *h = static_cast<host *>(handle);
    std::wstring wide(url);
    // The library takes UTF-8.
    int size = WideCharToMultiByte(CP_UTF8, 0, wide.c_str(), -1, nullptr, 0, nullptr, nullptr);
    std::string narrow(size > 0 ? size - 1 : 0, '\0');
    if (size > 1) WideCharToMultiByte(CP_UTF8, 0, wide.c_str(), -1, &narrow[0], size, nullptr, nullptr);
    h->view->navigate(narrow);
}

// Places the view in the parent's client area, in physical pixels.
CPW_API void cpw_set_bounds(void *handle, int x, int y, int width, int height) {
    auto *h = static_cast<host *>(handle);
    MoveWindow(h->widget, x, y, width, height, TRUE);
}

CPW_API void cpw_set_visible(void *handle, int visible) {
    auto *h = static_cast<host *>(handle);
    ShowWindow(h->widget, visible ? SW_SHOW : SW_HIDE);
    h->controller->put_IsVisible(visible ? TRUE : FALSE);
}

// Gives the page the keyboard.
CPW_API void cpw_focus(void *handle) {
    auto *h = static_cast<host *>(handle);
    h->controller->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
}

CPW_API void cpw_destroy(void *handle) {
    auto *h = static_cast<host *>(handle);
    if (!h) return;
    delete h->view;
    if (h->com) CoUninitialize();
    delete h;
}
