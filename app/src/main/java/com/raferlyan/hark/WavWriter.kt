package com.raferlyan.hark

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {
    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        val dataLen = samples.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(dataLen)
        for (s in samples) bb.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        file.writeBytes(bb.array())
    }
}
