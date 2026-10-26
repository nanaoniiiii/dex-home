package com.alkaidlab.dexlocal.core

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object ShellCommand {
    private val readers = Executors.newCachedThreadPool { r -> Thread(r, "DexLocal-command").apply { isDaemon = true } }
    fun run(vararg args: String): String {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val result = readers.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
        try {
            check(process.waitFor(8, TimeUnit.SECONDS)) { "${args.take(2).joinToString(" ")} 超时" }
            val output = result.get(1, TimeUnit.SECONDS).trim()
            check(process.exitValue() == 0 && !output.contains("SecurityException") && !output.contains("Exception occurred")) {
                output.take(700).ifEmpty { "系统命令失败 (${process.exitValue()})" }
            }
            return output
        } finally { process.destroy(); result.cancel(true) }
    }
}
