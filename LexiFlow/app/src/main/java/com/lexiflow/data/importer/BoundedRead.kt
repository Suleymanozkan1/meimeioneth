package com.lexiflow.data.importer

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads at most [limit] + 1 bytes, so callers can reject an oversized file without buffering all of it. */
fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0
    while (total <= limit) {
        val n = read(buf, 0, minOf(buf.size, limit + 1 - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}
