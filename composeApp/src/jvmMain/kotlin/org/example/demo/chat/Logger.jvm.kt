package org.example.demo.chat

actual object AppLogger {
    actual fun d(tag: String, message: String) {
        println("DEBUG/$tag: $message")
    }
    
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        println("ERROR/$tag: $message")
        throwable?.printStackTrace()
    }
    
    actual fun i(tag: String, message: String) {
        println("INFO/$tag: $message")
    }
    
    actual fun w(tag: String, message: String) {
        println("WARN/$tag: $message")
    }
}








