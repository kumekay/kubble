package coredevices.util.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAITranscriptionServiceTest {

    @Test
    fun buildWavWritesValidHeader() {
        val pcm = ByteArray(4) { 0 }
        val wav = buildWav(pcm, 16000)

        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals("WAVE", wav.decodeToString(8, 12))
        assertEquals("fmt ", wav.decodeToString(12, 16))
        assertEquals("data", wav.decodeToString(36, 40))

        fun intLe(offset: Int) =
            (wav[offset].toInt() and 0xFF) or
                ((wav[offset + 1].toInt() and 0xFF) shl 8) or
                ((wav[offset + 2].toInt() and 0xFF) shl 16) or
                ((wav[offset + 3].toInt() and 0xFF) shl 24)

        fun shortLe(offset: Int) =
            ((wav[offset].toInt() and 0xFF) or ((wav[offset + 1].toInt() and 0xFF) shl 8)).toShort().toInt()

        assertEquals(pcm.size + 36, intLe(4)) // chunk size
        assertEquals(1, shortLe(20)) // PCM format
        assertEquals(1, shortLe(22)) // mono
        assertEquals(16000, intLe(24)) // sample rate
        assertEquals(16, shortLe(34)) // bits per sample
        assertEquals(pcm.size, intLe(40)) // data size
    }

    @Test
    fun resampleIsNoOpWhenRatesMatch() {
        val pcm = byteArrayOf(1, 0, 2, 0)
        assertTrue(pcm.contentEquals(resamplePcm16(pcm, 16000, 16000)))
    }

    @Test
    fun resampleHalvesSampleCountWhenDownsampling() {
        val inputSamples = 100
        val pcm = ByteArray(inputSamples * 2)
        for (i in 0 until inputSamples) {
            pcm[i * 2] = (i and 0xFF).toByte()
            pcm[i * 2 + 1] = 0
        }
        val resampled = resamplePcm16(pcm, 16000, 8000)
        assertEquals(inputSamples / 2 * 2, resampled.size)
    }

    @Test
    fun resampleDoublesSampleCountWhenUpsampling() {
        val inputSamples = 50
        val pcm = ByteArray(inputSamples * 2)
        val resampled = resamplePcm16(pcm, 8000, 16000)
        assertEquals(inputSamples * 2 * 2, resampled.size)
    }
}
