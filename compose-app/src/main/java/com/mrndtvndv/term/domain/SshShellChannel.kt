package com.mrndtvndv.term.domain

import java.io.InputStream
import java.io.OutputStream

interface SshShellChannel {
    val inputStream: InputStream
    val outputStream: OutputStream
    fun resizeWindow(cols: Int, rows: Int, widthPx: Int, heightPx: Int)
    fun close()
}
