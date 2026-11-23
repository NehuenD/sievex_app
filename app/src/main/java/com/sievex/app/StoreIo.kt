package com.sievex.app

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Single-thread sequential disk persistence for JSON stores so readers never stall behind
 * DOM serialization + fsync() inside store locks.
 */
internal object StoreIo {

    val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sievex-store-io").apply { isDaemon = true }
    }

    fun writeAtomic(target: File, text: String) {
        val dir = target.parentFile
        if (dir == null) {
            target.writeText(text)
            return
        }
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}
