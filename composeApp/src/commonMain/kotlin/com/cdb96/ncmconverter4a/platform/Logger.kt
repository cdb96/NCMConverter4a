package com.cdb96.ncmconverter4a.platform

class Logger(private val tag: String) {
    fun i(message: String) = println("[$tag] I: $message")
    fun d(message: String) = println("[$tag] D: $message")
    fun e(message: String, throwable: Throwable? = null) = println("[$tag] E: $message ${throwable?.message}")
}
