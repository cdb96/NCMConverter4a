#pragma once

#include <jni.h>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <utility>

#include "ContextMemory.h"

namespace ncm {

// JNI exposes integer IDs, while native pointers keep their full width.
// A borrowed reference keeps a context alive when destroy races a decrypt call.
template <typename Context>
class ContextHandles {
public:
    jint add(JNIEnv* env, Context* context) {
        std::shared_ptr<Context> owned(context, destroyContext<Context>);
        std::lock_guard<std::mutex> lock(mutex_);
        // Never reuse an ID, so stale handles cannot address a later context.
        if (nextHandle_ > static_cast<std::uint32_t>(std::numeric_limits<jint>::max())) {
            jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
            if (exceptionClass != nullptr) env->ThrowNew(exceptionClass, "native decrypt context handles exhausted");
            return 0;
        }
        const auto handle = static_cast<jint>(nextHandle_++);
        contexts_.emplace(handle, std::move(owned));
        return handle;
    }

    std::shared_ptr<Context> get(jint handle) {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = contexts_.find(handle);
        return found == contexts_.end() ? nullptr : found->second;
    }

    void remove(jint handle) {
        std::shared_ptr<Context> removed;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto found = contexts_.find(handle);
            if (found == contexts_.end()) return;
            removed = std::move(found->second);
            contexts_.erase(found);
        }
    }

private:
    std::mutex mutex_;
    std::unordered_map<jint, std::shared_ptr<Context>> contexts_;
    std::uint32_t nextHandle_ = 1;
};

}  // namespace ncm
