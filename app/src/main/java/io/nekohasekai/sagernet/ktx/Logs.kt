package io.nekohasekai.sagernet.ktx

import libbox.Libbox
import java.io.InputStream
import java.io.OutputStream

object Logs {

    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    // level int use logrus.go

    fun d(message: String) {
        Libbox.nekoLogPrintln("[Debug] [${mkTag()}] $message")
    }

    fun d(message: String, exception: Throwable) {
        Libbox.nekoLogPrintln("[Debug] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun i(message: String) {
        Libbox.nekoLogPrintln("[Info] [${mkTag()}] $message")
    }

    fun i(message: String, exception: Throwable) {
        Libbox.nekoLogPrintln("[Info] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(message: String) {
        Libbox.nekoLogPrintln("[Warning] [${mkTag()}] $message")
    }

    fun w(message: String, exception: Throwable) {
        Libbox.nekoLogPrintln("[Warning] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(exception: Throwable) {
        Libbox.nekoLogPrintln("[Warning] [${mkTag()}] " + exception.stackTraceToString())
    }

    fun e(message: String) {
        Libbox.nekoLogPrintln("[Error] [${mkTag()}] $message")
    }

    fun e(message: String, exception: Throwable) {
        Libbox.nekoLogPrintln("[Error] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun e(exception: Throwable) {
        Libbox.nekoLogPrintln("[Error] [${mkTag()}] " + exception.stackTraceToString())
    }

}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}