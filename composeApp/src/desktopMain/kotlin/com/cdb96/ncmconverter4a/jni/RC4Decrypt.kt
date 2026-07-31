package com.cdb96.ncmconverter4a.jni
/**
 * 桌面端纯 Kotlin 实现（替代 C++/JNI 原生库 ncmc4a）。
 *
 * 状态使用 ThreadLocal（对应 C++ 的 thread_local），保证多线程并发转换互不干扰。
 * 内部用 ByteBuffer Long-XOR 加速，通过 wrap(ByteArray) 零拷贝回退到堆上执行。
 */
actual object RC4Decrypt {
    actual fun ksa(key: ByteArray) = Rc4Vector.ksa(key)
    actual fun prgaDecrypt(cipherData: ByteArray, bytesRead: Int) = Rc4Vector.decrypt(cipherData,bytesRead)
}
