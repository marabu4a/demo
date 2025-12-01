package org.example.demo.chat

import platform.Foundation.NSLog

actual object AppLogger {
    actual fun d(tag: String, message: String) {
        NSLog("DEBUG/$tag: $message")
    }
    
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        val errorMsg = if (throwable != null) {
            "$message\n${throwable.message}\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        NSLog("ERROR/$tag: $errorMsg")
    }
    
    actual fun i(tag: String, message: String) {
        NSLog("INFO/$tag: $message")
    }
    
    actual fun w(tag: String, message: String) {
        NSLog("WARN/$tag: $message")
    }
}

