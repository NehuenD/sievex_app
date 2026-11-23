package com.sievex.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal streaming 16-bit mono PCM WAV writer.
 *
 * A complete RIFF header is written when the file opens and the two length fields are patched on
 * close. A recording cut short by a crash or a kill therefore still parses as a WAV (with a zero
 * data length) instead of looking like an unknown file.
 */
class WavWriter(private val file: File, private val sampleRate: Int) {

    private val raf = RandomAccessFile(file, "rw")
    private val buffer = ByteArray(16 * 1024)
    private var bufPos = 0
    private var dataBytes = 0
    private var closed = false

    init {
        try {
            raf.setLength(0)
            raf.write(header(0))
        } catch (t: Throwable) {
            try {
                raf.close()
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    private fun flushBuffer() {
        if (bufPos > 0) {
            raf.write(buffer, 0, bufPos)
            bufPos = 0
        }
    }

    fun write(pcm: ByteArray, length: Int) {
        if (closed || length <= 0) return
        if (bufPos + length > buffer.size) {
            flushBuffer()
            if (length >= buffer.size) {
                raf.write(pcm, 0, length)
            } else {
                System.arraycopy(pcm, 0, buffer, 0, length)
                bufPos = length
            }
        } else {
            System.arraycopy(pcm, 0, buffer, bufPos, length)
            bufPos += length
        }
        dataBytes += length
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            flushBuffer()
            raf.seek(0)
            raf.write(header(dataBytes))
        } catch (_: Throwable) {
            // The audio is already on disk; a failed size patch must not crash the recorder.
        }
        try {
            raf.close()
        } catch (_: Throwable) {
        }
    }

    /** The 44-byte canonical PCM header for [dataSize] bytes of 16-bit mono samples. */
    private fun header(dataSize: Int): ByteArray {
        val bb = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataSize)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1.toShort())
        bb.putShort(1.toShort())
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * 2)
        bb.putShort(2.toShort())
        bb.putShort(16.toShort())
        bb.put("data".toByteArray())
        bb.putInt(dataSize)
        return bb.array()
    }

    private companion object {
        const val HEADER_SIZE = 44
    }
}
