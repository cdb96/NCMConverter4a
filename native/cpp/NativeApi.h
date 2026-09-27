// Stable C ABI exported by the shared native core.
//
// Both the Android and the Desktop build link this single implementation; only
// the bridge (JNI) differs per platform. Keep this header free of JNI types so
// the algorithms stay testable without a JVM.
#ifndef NCM_NATIVE_API_H
#define NCM_NATIVE_API_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define NCM_API __declspec(dllexport)
#else
#define NCM_API __attribute__((visibility("default")))
#endif

/* RC4 ---------------------------------------------------------------------
 * Each conversion owns a context. Every decrypt call restarts at keystream
 * byte 0, so callers pass 256-byte aligned chunks except for the final one.
 */
typedef struct NcmRc4Context NcmRc4Context;
NCM_API NcmRc4Context* ncm_rc4_create(const uint8_t* key, int key_len);
NCM_API void ncm_rc4_decrypt(NcmRc4Context* context, uint8_t* data, int length);
NCM_API void ncm_rc4_destroy(NcmRc4Context* context);

/* KGM ---------------------------------------------------------------------
 * ncm_kgm_create() installs the per-file key; ncm_kgm_decrypt() decrypts the
 * chunk `data[0, length)` whose absolute position in the stream is `offset`.
 * It returns the next absolute stream position.
 *
 * `offset` must be a multiple of 16: the decryption loop consumes 16-byte blocks
 * and derives its mask cursors from the absolute position, so an unaligned
 * offset reads past the internal 272-byte mask cycle. Callers get this for free
 * by threading the returned position back in while always filling a buffer of a
 * multiple of 16 bytes, which is what the converters do (256 KiB reads, so only
 * the final chunk is shorter). `length` itself may be arbitrary.
 */
typedef struct NcmKgmContext NcmKgmContext;
NCM_API NcmKgmContext* ncm_kgm_create(const uint8_t* key, int key_len);
NCM_API int ncm_kgm_decrypt(NcmKgmContext* context, uint8_t* data, int offset, int length);
NCM_API void ncm_kgm_destroy(NcmKgmContext* context);

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif /* NCM_NATIVE_API_H */
