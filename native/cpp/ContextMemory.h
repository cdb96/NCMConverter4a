// Context allocation shared by RC4 and KGM. Android only needs libc for
// allocation; out-of-memory errors are reported by the JNI bridge.
#pragma once

#include <cstddef>
#include <cstdlib>
#include <new>
#include <type_traits>

namespace ncm {

template <typename Context>
Context* createContext() {
#if defined(__ANDROID__)
    // malloc covers these contexts' SIMD alignment. Placement new starts the
    // C++ object lifetime and preserves value initialization without pulling
    // libc++'s allocating new and exception machinery into the Android .so.
    static_assert(alignof(Context) <= alignof(std::max_align_t));
    static_assert(std::is_nothrow_default_constructible_v<Context>);
    void* storage = std::malloc(sizeof(Context));
    return storage == nullptr ? nullptr : new (storage) Context{};
#else
    return new (std::nothrow) Context{};
#endif
}

template <typename Context>
void destroyContext(Context* context) {
#if defined(__ANDROID__)
    static_assert(std::is_nothrow_destructible_v<Context>);
    if (context != nullptr) {
        context->~Context();
        std::free(context);
    }
#else
    delete context;
#endif
}

}  // namespace ncm
