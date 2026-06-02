package com.example.federatedapp.federated

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal protobuf + gRPC frame encoder/decoder.
 *
 * Proto3 packed encoding düzeltmesi:
 *   repeated float  → wire type 2 (packed, default proto3) VEYA wire type 5 (non-packed)
 *   repeated string → wire type 2 (her eleman ayrı tag + length)
 */
internal object GrpcProtoCodec {

    // ── gRPC frame ────────────────────────────────────────────────────────────

    fun grpcWrap(payload: ByteArray): ByteArray {
        val out = ByteArray(5 + payload.size)
        out[0] = 0
        val len = payload.size
        out[1] = (len ushr 24).toByte()
        out[2] = (len ushr 16).toByte()
        out[3] = (len ushr 8).toByte()
        out[4] = len.toByte()
        payload.copyInto(out, 5)
        return out
    }

    fun grpcUnwrap(data: ByteArray): ByteArray {
        if (data.size < 5) return ByteArray(0)
        val len = ((data[1].toInt() and 0xFF) shl 24) or
                ((data[2].toInt() and 0xFF) shl 16) or
                ((data[3].toInt() and 0xFF) shl 8) or
                (data[4].toInt() and 0xFF)
        return data.copyOfRange(5, minOf(5 + len, data.size))
    }

    // ── FitRequest encoder ────────────────────────────────────────────────────
    // message FitRequest { repeated float weights = 1; int32 num_samples = 2; }
    // Packed encoding kullan (proto3 default, Python decoder her ikisini de anlar)

    fun encodeFitRequest(weights: List<Float>, numSamples: Int): ByteArray {
        val buf = ByteArrayOutputStream()

        // Field 1: repeated float → packed (wire type 2)
        if (weights.isNotEmpty()) {
            buf.write(0x0A)  // tag: (1 << 3) | 2
            writeVarint(buf, (weights.size * 4).toLong())
            for (w in weights) writeFixed32(buf, java.lang.Float.floatToRawIntBits(w))
        }

        // Field 2: int32 num_samples (wire type 0)
        if (numSamples != 0) {
            buf.write(0x10)  // tag: (2 << 3) | 0
            writeVarint(buf, numSamples.toLong())
        }
        return buf.toByteArray()
    }

    // ── FitResponse decoder ───────────────────────────────────────────────────
    // message FitResponse {
    //   repeated float  global_weights = 1;   ← packed (wire 2) veya non-packed (wire 5)
    //   repeated string categories     = 2;   ← her eleman wire 2
    //   int32           round          = 3;
    // }

    data class FitResponse(val globalWeights: List<Float>, val categories: List<String>, val round: Int)

    fun decodeFitResponse(bytes: ByteArray): FitResponse {
        val weights = mutableListOf<Float>()
        val cats = mutableListOf<String>()
        var round = 0
        val input = ByteArrayInputStream(bytes)

        while (input.available() > 0) {
            val tag = readVarint(input).toInt()
            val field = tag ushr 3
            val wire = tag and 7

            when {
                // Field 1: repeated float — packed (proto3 default)
                field == 1 && wire == 2 -> {
                    val dataLen = readVarint(input).toInt()
                    val count = dataLen / 4
                    repeat(count) { weights.add(readFloat(input)) }
                }
                // Field 1: repeated float — non-packed (her eleman ayrı)
                field == 1 && wire == 5 -> weights.add(readFloat(input))

                // Field 2: repeated string
                field == 2 && wire == 2 -> cats.add(readString(input))

                // Field 3: int32 round
                field == 3 && wire == 0 -> round = readVarint(input).toInt()

                else -> skipField(input, wire)
            }
        }
        return FitResponse(weights, cats, round)
    }

    // ── ModelResponse decoder ─────────────────────────────────────────────────
    // message ModelResponse { repeated float weights = 1; repeated string categories = 2; }

    data class ModelResponse(val weights: List<Float>, val categories: List<String>)

    fun decodeModelResponse(bytes: ByteArray): ModelResponse {
        val weights = mutableListOf<Float>()
        val cats = mutableListOf<String>()
        val input = ByteArrayInputStream(bytes)

        while (input.available() > 0) {
            val tag = readVarint(input).toInt()
            val field = tag ushr 3
            val wire = tag and 7

            when {
                field == 1 && wire == 2 -> {  // packed float
                    val dataLen = readVarint(input).toInt()
                    repeat(dataLen / 4) { weights.add(readFloat(input)) }
                }
                field == 1 && wire == 5 -> weights.add(readFloat(input))  // non-packed
                field == 2 && wire == 2 -> cats.add(readString(input))
                else -> skipField(input, wire)
            }
        }
        return ModelResponse(weights, cats)
    }

    fun encodeEmpty(): ByteArray = ByteArray(0)

    // ── Low-level primitives ──────────────────────────────────────────────────

    private fun writeFixed32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and (-0x80L) != 0L) {
            out.write((v and 0x7F or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun readFixed32(input: ByteArrayInputStream): Int {
        val b = ByteArray(4)
        input.read(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readFloat(input: ByteArrayInputStream) =
        java.lang.Float.intBitsToFloat(readFixed32(input))

    private fun readVarint(input: ByteArrayInputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    private fun readString(input: ByteArrayInputStream): String {
        val len = readVarint(input).toInt()
        val b = ByteArray(len)
        input.read(b)
        return String(b, Charsets.UTF_8)
    }

    private fun skipField(input: ByteArrayInputStream, wireType: Int) {
        when (wireType) {
            0 -> readVarint(input)
            1 -> input.skip(8)
            2 -> input.skip(readVarint(input))
            5 -> input.skip(4)
        }
    }
}
