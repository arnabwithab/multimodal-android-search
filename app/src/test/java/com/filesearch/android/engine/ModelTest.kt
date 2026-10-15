package com.filesearch.android.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {

    // nextNeeded staging

    @Test
    fun `empty have needs 270m`() {
        assertEquals(ModelPacks.TEXT_270M, ModelManager.nextNeeded(emptySet(), false))
    }

    @Test
    fun `have 270m needs 440m`() {
        assertEquals(ModelPacks.TEXT_VISION_440M, ModelManager.nextNeeded(setOf("text-270m"), false))
    }

    @Test
    fun `out-of-order have 440m still needs 270m`() {
        assertEquals(ModelPacks.TEXT_270M, ModelManager.nextNeeded(setOf("text-vision-440m"), false))
    }

    @Test
    fun `have 270m and 440m with audio needs 740m`() {
        assertEquals(
            ModelPacks.FULL_740M,
            ModelManager.nextNeeded(setOf("text-270m", "text-vision-440m"), true),
        )
    }

    @Test
    fun `satisfied returns null`() {
        assertNull(ModelManager.nextNeeded(setOf("text-270m", "text-vision-440m"), false))
        assertNull(
            ModelManager.nextNeeded(setOf("text-270m", "text-vision-440m", "full-740m"), true),
        )
    }

    // pickArtifact

    @Test
    fun `exact soc match wins`() {
        val artifacts = listOf("model_generic-npu.litertlm", "model_Qualcomm_SM8xxx.litertlm", "model_gpu.litertlm")
        assertEquals("model_Qualcomm_SM8xxx.litertlm", ModelManager.pickArtifact("Qualcomm_SM8xxx", artifacts))
    }

    @Test
    fun `empty artifacts returns cpu sentinel`() {
        assertEquals("cpu", ModelManager.pickArtifact("Qualcomm_SM8xxx", emptyList()))
    }

    @Test
    fun `pixel tpu artifact picked as npu tier`() {
        val artifacts = listOf("model_gpu.litertlm", "model_Tensor_G5-tpu.litertlm", "model_cpu-xnnpack.litertlm")
        assertEquals("model_Tensor_G5-tpu.litertlm", ModelManager.pickArtifact("Unknown_SoC_9", artifacts))
    }

    @Test
    fun `gpu picked when no npu build`() {
        val artifacts = listOf("model_gpu.litertlm", "model_cpu-xnnpack.litertlm")
        assertEquals("model_gpu.litertlm", ModelManager.pickArtifact("Unknown_SoC_9", artifacts))
    }

    @Test
    fun `blank soc skips exact match`() {
        val artifacts = listOf("model_gpu.litertlm", "model_cpu-xnnpack.litertlm")
        assertEquals("model_gpu.litertlm", ModelManager.pickArtifact("   ", artifacts))
    }

    @Test
    fun `unknown soc falls back to cpu without throwing`() {
        val artifacts = listOf("model_gpu.litertlm", "model_cpu-xnnpack.litertlm")
        assertEquals("model_cpu-xnnpack.litertlm", ModelManager.pickArtifact("NoSuchChip", listOf("model_cpu-xnnpack.litertlm")))
        assertEquals("model_gpu.litertlm", ModelManager.pickArtifact("NoSuchChip", artifacts))
    }

    // shaOk

    @Test
    fun `shaOk matches known vector`() {
        assertTrue(ModelManager.shaOk("abc".toByteArray(), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
    }

    @Test
    fun `shaOk accepts uppercase hex`() {
        assertTrue(ModelManager.shaOk("abc".toByteArray(), "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
    }

    @Test
    fun `shaOk rejects mismatch`() {
        assertFalse(ModelManager.shaOk("abc".toByteArray(), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ae"))
    }

    @Test
    fun `shaOk stream overload matches known vector`() {
        assertTrue(
            ModelManager.shaOk(
                "abc".byteInputStream(),
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ),
        )
        assertFalse(
            ModelManager.shaOk(
                "abc".byteInputStream(),
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ae",
            ),
        )
    }

    @Test
    fun `shaOk handles empty bytes`() {
        assertTrue(
            ModelManager.shaOk(
                ByteArray(0),
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ),
        )
        assertFalse(ModelManager.shaOk(ByteArray(0), "00"))
    }

    // fingerprintOk

    @Test
    fun `fingerprintOk null stored is false`() {
        assertFalse(ModelManager.fingerprintOk(null, "embeddinggemma2@r1 cpu fp16 256 budgets"))
    }

    @Test
    fun `fingerprintOk match is true`() {
        assertTrue(ModelManager.fingerprintOk("m@r cpu fp16 256 b", "m@r cpu fp16 256 b"))
    }

    @Test
    fun `fingerprintOk mismatch is false`() {
        assertFalse(ModelManager.fingerprintOk("m@r1 cpu fp16 256 b", "m@r2 cpu fp16 256 b"))
    }
}
