#include <windows.h>
#include <msi.h>
#include <msiquery.h>
#include <wchar.h>

static wchar_t* getProperty(MSIHANDLE session, const wchar_t* name) {
    DWORD length = 0;
    wchar_t empty = L'\0';
    if (MsiGetPropertyW(session, name, &empty, &length) != ERROR_MORE_DATA || length == 0) {
        return NULL;
    }

    wchar_t* value = HeapAlloc(GetProcessHeap(), 0, (length + 1) * sizeof(wchar_t));
    if (value == NULL) {
        return NULL;
    }
    DWORD capacity = length + 1;
    if (MsiGetPropertyW(session, name, value, &capacity) != ERROR_SUCCESS) {
        HeapFree(GetProcessHeap(), 0, value);
        return NULL;
    }
    return value;
}

static wchar_t* getInstallLocation(const wchar_t* productCode) {
    DWORD length = 0;
    wchar_t empty = L'\0';
    if (MsiGetProductInfoW(productCode, L"InstallLocation", &empty, &length) != ERROR_MORE_DATA ||
        length == 0) {
        return NULL;
    }

    wchar_t* value = HeapAlloc(GetProcessHeap(), 0, (length + 1) * sizeof(wchar_t));
    if (value == NULL) {
        return NULL;
    }
    DWORD capacity = length + 1;
    if (MsiGetProductInfoW(productCode, L"InstallLocation", value, &capacity) != ERROR_SUCCESS) {
        HeapFree(GetProcessHeap(), 0, value);
        return NULL;
    }
    return value;
}

static UINT restoreFromRelatedProperty(MSIHANDLE session, const wchar_t* propertyName) {
    wchar_t* relatedProducts = getProperty(session, propertyName);
    if (relatedProducts == NULL) {
        return ERROR_SUCCESS;
    }

    wchar_t* productCode = relatedProducts;
    for (;;) {
        wchar_t* separator = wcschr(productCode, L';');
        if (separator != NULL) {
            *separator = L'\0';
        }
        wchar_t* location = getInstallLocation(productCode);
        if (location != NULL && *location != L'\0') {
            UINT result = MsiSetPropertyW(session, L"INSTALLDIR", location);
            HeapFree(GetProcessHeap(), 0, location);
            HeapFree(GetProcessHeap(), 0, relatedProducts);
            return result;
        }
        if (location != NULL) {
            HeapFree(GetProcessHeap(), 0, location);
        }
        if (separator == NULL) {
            break;
        }
        productCode = separator + 1;
    }

    HeapFree(GetProcessHeap(), 0, relatedProducts);
    return ERROR_SUCCESS;
}

__declspec(dllexport) UINT __stdcall RestoreInstallDir(MSIHANDLE session) {
    // FindRelatedProducts fills these with ProductCodes eligible for removal.
    UINT result = restoreFromRelatedProperty(session, L"JP_UPGRADABLE_FOUND");
    if (result != ERROR_SUCCESS) {
        return result;
    }
    return restoreFromRelatedProperty(session, L"NCM_LEGACY_UPGRADABLE_FOUND");
}
