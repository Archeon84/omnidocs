package com.omnidocs.app.ai

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Validates a GGUF file's header before it reaches the native ggml/llama.cpp loader,
 * so a model whose tensor types ggml cannot consume never crashes the process via
 * GGML_ABORT (which calls abort() unconditionally and is not recoverable from Kotlin).
 *
 * ggml's scalar binary ops (add/mul) only support f32/f16/bf16 operand combinations.
 * If a GGUF carries 1-D bias/norm tensors (or the token-type embedding table) as a
 * block-quantized type (Q8_0, Q4_0, ...), those tensors flow directly into
 * `ggml_add`/`ggml_mul` as quantized operands and abort with `binary-op: unsupported
 * types`. This validator rejects such models before any native call, so the caller
 * can fall back gracefully instead of dying.
 *
 * Layout is mirrored from gguf-py's GGUFReader (llama.cpp/gguf-py/gguf/gguf_reader.py):
 *   header:  magic u32, version u32, tensor_count u64, kv_count u64
 *   kv pairs:  key string, value-type u32, value (scalar/string/array)   -- unpadded
 *   tensor infos follow immediately (NO alignment between kv and tensor sections):
 *     name string, n_dims u32, dims u64[n_dims], ggml_type u32, offset u64
 * Alignment (default 32) applies only to the tensor DATA section, which we never read.
 */
object GgufTensorValidator {

    private const val GGUF_MAGIC: Int = 0x46554747 // "GGUF"
    private const val GGUF_VERSION_MIN = 2
    private const val GGUF_VERSION_MAX = 5

    // Float scalar types ggml's binary ops accept for both operands.
    private val FLOAT_TYPE_NAMES = setOf("F32", "F16", "BF16")

    data class ValidationResult(val valid: Boolean, val reason: String? = null) {
        companion object {
            fun ok() = ValidationResult(valid = true)
            fun fail(reason: String) = ValidationResult(valid = false, reason = reason)
        }
    }

    /**
     * Validate the GGUF at [file]. ok() => safe for the native loader; fail(reason) =>
     * it would crash or is not a parseable GGUF.
     *
     * @param requireEmbeddingLayout when true (default), enforce the rule that all
     *   1-D tensors (biases, norm weights, token-type embeddings) must be float
     *   (F32/F16/BF16). Quantized tensors are only tolerated as >=2-D weight matrices.
     *   When false, only structural parseability is checked (used for generative models,
     *   which follow the same convention but are not load-gated on it).
     */
    fun validate(file: File, requireEmbeddingLayout: Boolean = true): ValidationResult {
        return try {
            BufferedInputStream(file.inputStream()).use { parse(it, requireEmbeddingLayout) }
        } catch (e: EOFException) {
            ValidationResult.fail("Truncated GGUF header (corrupt or partial download)")
        } catch (e: Exception) {
            ValidationResult.fail("Not a valid GGUF: ${e.message}")
        }
    }

    private fun parse(input: BufferedInputStream, gate1d: Boolean): ValidationResult {
        if (input.readIntLE() != GGUF_MAGIC) {
            return ValidationResult.fail("Bad magic — not a GGUF file")
        }
        val version = input.readIntLE()
        if (version < GGUF_VERSION_MIN || version > GGUF_VERSION_MAX) {
            return ValidationResult.fail("Unsupported GGUF version $version")
        }
        val tensorCount = input.readLongLE()
        val kvCount = input.readLongLE()

        // Skip KV metadata: key string + typed value. Strings are NOT padded, and
        // there is no alignment padding between kv pairs or before tensor infos.
        repeat(safeCount(kvCount, "kv")) {
            input.skipString()
            input.skipValue() // reads value-type u32 internally
        }
        if (tensorCount < 0 || tensorCount > 1_000_000) {
            return ValidationResult.fail("Absurd tensor count $tensorCount")
        }

        // Walk tensor infos: name string, n_dims u32, dims u64[n_dims], type u32,
        // offset u64 (offset only skipped — data section never read).
        repeat(tensorCount.toInt()) {
            val name = input.readString()
            val ndim = input.readIntLE()
            if (ndim < 1 || ndim > 4) {
                return ValidationResult.fail("Bad tensor dims ($ndim) for '$name'")
            }
            repeat(ndim) { input.readLongLE() } // dims are u64 each
            val typeName = ggmlTypeName(input.readIntLE())
            input.readLongLE() // tensor offset u64

            if (gate1d && ndim <= 1 && !FLOAT_TYPE_NAMES.contains(typeName)) {
                return ValidationResult.fail(
                    "Tensor '$name' is 1-D type $typeName; ggml add/mul would abort on " +
                    "this tensor. This embedding model is not llama.cpp-safe."
                )
            }
        }
        return ValidationResult.ok()
    }

    private fun safeCount(v: Long, what: String): Int {
        if (v < 0 || v > 100_000_000) throw IllegalArgumentException("Absurd $what count $v")
        return v.toInt()
    }

    // ---- low-level little-endian GGUF readers ----

    private fun BufferedInputStream.readIntLE(): Int {
        val b = ByteArray(4); if (!readFull(b)) throw EOFException()
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun BufferedInputStream.readLongLE(): Long {
        val b = ByteArray(8); if (!readFull(b)) throw EOFException()
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun BufferedInputStream.readFull(buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun BufferedInputStream.readString(): String {
        val len = readLongLE()
        if (len < 0 || len > 4096) throw IllegalArgumentException("GGUF string length $len")
        val b = ByteArray(len.toInt()); if (!readFull(b)) throw EOFException()
        return String(b, StandardCharsets.UTF_8)
    }

    private fun BufferedInputStream.skipString() {
        val len = readLongLE()
        if (len < 0 || len > 100_000_000) throw IllegalArgumentException("Bad string length $len")
        skipExactly(len.toInt())
    }

    /** Skip one GGUF value; [this] must be positioned at the value-type u32. */
    private fun BufferedInputStream.skipValue() {
        when (readIntLE()) {
            0, 1, 7 -> read()                    // UINT8 / INT8 / BOOL
            2, 3 -> skipExactly(2)               // UINT16 / INT16
            4, 5, 6 -> skipExactly(4)            // UINT32 / INT32 / FLOAT32
            8 -> skipString()                    // STRING (unpadded)
            9 -> {                               // ARRAY: elem-type u32, count u64, then elements
                val elemType = readIntLE()
                val count = readLongLE()
                safeCount(count, "array")
                repeat(count.toInt()) { skipTypedValue(elemType) }
            }
            10, 11, 12 -> skipExactly(8)         // UINT64 / INT64 / FLOAT64
            else -> throw IllegalArgumentException("Unknown GGUF value type")
        }
    }

    private fun BufferedInputStream.skipTypedValue(type: Int) {
        when (type) {
            0, 1, 7 -> read()
            2, 3 -> skipExactly(2)
            4, 5, 6 -> skipExactly(4)
            8 -> skipString()
            9 -> throw IllegalArgumentException("Nested GGUF arrays unsupported")
            10, 11, 12 -> skipExactly(8)
            else -> throw IllegalArgumentException("Unknown GGUF array elem type $type")
        }
    }

    private fun BufferedInputStream.skipExactly(n: Int) {
        var remaining = n
        while (remaining > 0) {
            val skipped = skip(remaining.toLong())
            if (skipped > 0) {
                remaining -= skipped.toInt()
            } else if (read() < 0) {
                throw EOFException()
            } else {
                remaining -= 1
            }
        }
    }

    // ---- ggml_type enum -> name (mirrors ggml.h) ----

    internal fun ggmlTypeName(t: Int): String = when (t) {
        0 -> "F32"; 1 -> "F16"; 2 -> "BF16"
        3 -> "Q4_0"; 4 -> "Q4_1"; 5 -> "Q5_0"; 6 -> "Q5_1"; 7 -> "Q8_0"; 8 -> "Q8_1"
        9 -> "Q2_K"; 10 -> "Q3_K"; 11 -> "Q4_K"; 12 -> "Q5_K"; 13 -> "Q6_K"; 14 -> "Q8_K"
        15 -> "IQ2_XXS"; 16 -> "IQ2_XS"; 17 -> "IQ3_XXS"; 18 -> "IQ1_S"
        19 -> "IQ4_NL"; 20 -> "IQ3_S"; 21 -> "IQ2_S"; 22 -> "IQ4_XS"
        23 -> "I8"; 24 -> "I16"; 25 -> "I32"; 26 -> "I64"; 27 -> "F64"
        28 -> "IQ1_M"; 29 -> "IQ4_K"; 30 -> "IQ2_H"; 31 -> "IQ3_K"
        else -> "UNKNOWN($t)"
    }
}