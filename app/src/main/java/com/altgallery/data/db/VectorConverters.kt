package com.altgallery.data.db

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Embedding vectors are stored as raw little-endian float32 bytes in Room.
// 384 floats => 1536 bytes per image.

fun FloatArray.toByteArray(): ByteArray {
    val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    forEach { buffer.putFloat(it) }
    return buffer.array()
}

fun ByteArray.toFloatArray(): FloatArray {
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(size / Float.SIZE_BYTES)
    for (i in out.indices) out[i] = buffer.float
    return out
}
