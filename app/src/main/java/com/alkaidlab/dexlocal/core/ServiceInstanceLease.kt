package com.alkaidlab.dexlocal.core

import android.os.Process
import com.alkaidlab.dexlocal.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileLock

/** Serialize this app's shell helper across compatible permission managers. */
internal class ServiceInstanceLease : AutoCloseable {
    private val file = File("/data/local/tmp/dexlocal-service-owner-v1.lock")
    private val channel = RandomAccessFile(file, "rw").channel
    private var lock: FileLock? = null
    private val name = "${BuildConfig.APPLICATION_ID}:dexhome"
    private fun verified(pid: Int): Boolean = try {
        if (pid <= 1 || pid == Process.myPid()) false else {
            val uid = Regex("(?m)^Uid:\\s+(\\d+)").find(File("/proc/$pid/status").readText())?.groupValues?.get(1)?.toIntOrNull()
            val command = File("/proc/$pid/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
            uid == Process.myUid() && command == name
        }
    } catch (_: Exception) { false }
    private fun retire(pid: Int) { if (verified(pid)) Process.sendSignal(pid, 15) }
    init {
        repeat(40) {
            if (lock == null) {
                lock = channel.tryLock()
                if (lock == null) {
                    file.readText().trim().toIntOrNull()?.let { retire(it) }
                    Thread.sleep(100)
                }
            }
        }
        check(lock != null) { "旧接收服务尚未完成交接，请稍后重试" }
        // Older versions did not hold the lock. Only retire exact same-name,
        // same-UID helpers; never touch the manager or another app's process.
        val peers = ShellCommand.run("ps", "-A", "-o", "PID,UID,NAME")
        for (line in peers.lineSequence()) {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size >= 3 && fields.last() == name) fields.first().toIntOrNull()?.let { retire(it) }
        }
        channel.truncate(0); channel.position(0)
        channel.write(ByteBuffer.wrap(Process.myPid().toString().toByteArray())); channel.force(true)
        android.system.Os.chmod(file.path, 384)
    }
    override fun close() { try { lock?.release() } finally { channel.close() } }
}
