#pragma once

#include <jni.h>
#include <atomic>
#include <cstdint>
#include <limits>
#include <memory>
#include <unordered_map>
#include <utility>

#include "ContextMemory.h"

namespace ncm {

// Each synchronous conversion owns a context on its worker thread. The local
// table preserves Int handles without sharing context ownership across workers.
template <typename Context>
class ContextHandles {
public:
    jint add(JNIEnv* env, Context* context) {
        OwnedContext owned(context, destroyContext<Context>);
        auto next = nextHandle_.load(std::memory_order_relaxed);
        // Unique IDs make stale handles and calls from another worker invalid.
        while (next <= static_cast<std::uint32_t>(std::numeric_limits<jint>::max())) {
            if (nextHandle_.compare_exchange_weak(next, next + 1, std::memory_order_relaxed)) {
                const auto handle = static_cast<jint>(next);
                contexts_.emplace(handle, std::move(owned));
                return handle;
            }
        }
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) env->ThrowNew(exceptionClass, "native decrypt context handles exhausted");
        return 0;
    }

    Context* get(jint handle) const {
        const auto found = contexts_.find(handle);
        return found == contexts_.end() ? nullptr : found->second.get();
    }

    void remove(jint handle) {
        contexts_.erase(handle);
    }

private:
    using OwnedContext = std::unique_ptr<Context, decltype(&destroyContext<Context>)>;
    std::unordered_map<jint, OwnedContext> contexts_;
    static_assert(std::atomic<std::uint32_t>::is_always_lock_free);
    inline static std::atomic<std::uint32_t> nextHandle_{1};
};

}  // namespace ncm
