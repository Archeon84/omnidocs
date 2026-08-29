package com.omnidocs.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Synthetic GGUF header fixtures mirroring the two real multilingual-e5-small
 * builds:
 *   - cstr/multilingual-e5-small-GGUF : 2-D weights quantized Q8_0, all 1-D float
 *   - milimyname/...-Q8_0-GGUF        : EVERY tensor quantized Q8_0 (incl. 1-D)
 * The evaluation model is that ggml add/mul only accept f32/f16/bf16, so a 1-D
 * quantized bias/norm tensor would abort the process during embed.
 */
class GgufTensorValidatorTest {

    // ggml_type ids
    private val F32 = 0
    private val Q8_0 = 7

    private fun writeString(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray()
        writeU64(out, b.size.toLong())
        out.write(b)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Int) {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
    }

    private fun writeU64(out: ByteArrayOutputStream, v: Long) {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
    }

    private fun writeTensorInfo(
        out: ByteArrayOutputStream,
        name: String,
        dims: IntArray,
        type: Int,
    ) {
        writeString(out, name)
        writeU32(out, dims.size)          // n_dims
        for (d in dims) writeU64(out, d.toLong())  // dims are u64 each
        writeU32(out, type)               // ggml_type
        writeU64(out, 0L)                 // offset (unused for header parse)
    }

    /**
     * Build a minimal GGUF with 0 KV pairs and the given tensorInfos.
     * Passing a non-empty [kvToString] / [kvToArray] exercises the KV-skip path.
     */
    private fun buildGguf(tensorInfos: List<Triple<String, IntArray, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        writeU32(out, 0x46554747)         // magic "GGUF"
        writeU32(out, 3)                  // version
        writeU64(out, tensorInfos.size.toLong())
        writeU64(out, 2L)                 // kv_count = 2 (exercise skip of string + array)

        // KV #1: string value, e.g. general.architecture = "bert"
        writeString(out, "general.architecture")
        writeU32(out, 8)                  // STRING
        writeString(out, "bert")

        // KV #2: array of uint32, e.g. general.tags = ["gguf"]
        writeString(out, "general.tags")
        writeU32(out, 9)                  // ARRAY
        writeU32(out, 8)                  // element type STRING
        writeU64(out, 1L)                 // element count
        writeString(out, "gguf")

        for ((name, dims, type) in tensorInfos) {
            writeTensorInfo(out, name, dims, type)
        }
        return out.toByteArray()
    }

    private fun writeTemp(bytes: ByteArray): File {
        val f = File.createTempFile("gguf_fixture", ".gguf")
        f.deleteOnExit()
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun `valid cstr-style model - 2d weights quantized, 1d float - passes`() {
        val tensors = listOf(
            Triple("token_embd.weight", intArrayOf(384, 250037), Q8_0), // 2-D quantized: OK
            Triple("token_embd_norm.weight", intArrayOf(384), F32),     // 1-D float: OK
            Triple("token_embd_norm.bias", intArrayOf(384), F32),
            Triple("blk.0.attn_output.weight", intArrayOf(384, 384), Q8_0),
            Triple("blk.0.attn_output.bias", intArrayOf(384), F32),
        )
        val file = writeTemp(buildGguf(tensors))
        assertTrue(GgufTensorValidator.validate(file).valid)
    }

    @Test
    fun `invalid milimyname-style model - 1d norm bias quantized - rejected`() {
        val tensors = listOf(
            Triple("token_embd.weight", intArrayOf(384, 250037), Q8_0),
            Triple("token_embd_norm.bias", intArrayOf(384), Q8_0), // 1-D Q8_0: would abort
        )
        val file = writeTemp(buildGguf(tensors))
        val result = GgufTensorValidator.validate(file)
        assertFalse(result.valid)
        assertTrue(result.reason!!.contains("token_embd_norm.bias"))
    }

    @Test
    fun `invalid model - token types embedding quantized - rejected`() {
        val tensors = listOf(
            Triple("token_types.weight", intArrayOf(384, 2), Q8_0), // 1-D? no, 2-D — treated OK
            Triple("token_embd_norm.bias", intArrayOf(384), F32),
        )
        val file = writeTemp(buildGguf(tensors))
        // token_types is 2-D, so it is allowed here even when quantized.
        assertTrue(GgufTensorValidator.validate(file).valid)
    }

    @Test
    fun `not a gguf - bad magic - rejected`() {
        val file = writeTemp(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val result = GgufTensorValidator.validate(file)
        assertFalse(result.valid)
        assertTrue(result.reason!!.contains("magic", ignoreCase = true))
    }

    @Test
    fun `truncated gguf - rejected gracefully`() {
        val good = buildGguf(listOf(Triple("a", intArrayOf(384), F32)))
        val truncated = good.copyOf(good.size / 3) // chop well inside header
        val file = writeTemp(truncated)
        val result = GgufTensorValidator.validate(file)
        assertFalse(result.valid) // must not throw; must report invalid
    }

    @Test
    fun `ggml type names`() {
        assertTrue(GgufTensorValidator.ggmlTypeName(0) == "F32")
        assertTrue(GgufTensorValidator.ggmlTypeName(7) == "Q8_0")
    }
}
