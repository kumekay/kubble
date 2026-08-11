package coredevices.util.transcription

import co.touchlab.kermit.Logger
import coredevices.api.ApiClient
import coredevices.util.AudioEncoding
import coredevices.util.CommonBuildKonfig
import coredevices.util.writeWavHeader
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Single-shot transcription via OpenAI's audio transcription API (gpt-transcribe).
 * Enabled when `openaiApiKey` is set in local.properties/gradle.properties.
 */
class OpenAITranscriptionService :
    ApiClient(CommonBuildKonfig.USER_AGENT_VERSION, timeout = REQUEST_TIMEOUT),
    TranscriptionService {

    companion object {
        private val logger = Logger.withTag("OpenAITranscriptionService")
        private const val TRANSCRIBE_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val MODEL = "gpt-transcribe"
        private const val TARGET_SAMPLE_RATE = 16000
        private const val MODEL_USED = "openai/gpt-transcribe"
        // API limit is 25MB per file.
        private const val MAX_AUDIO_BYTES = 25 * 1024 * 1024
        private val REQUEST_TIMEOUT = 60.seconds
    }

    @Serializable
    private data class OpenAITranscriptionResponse(
        val text: String? = null,
    )

    override val onInitialized: Channel<Boolean> = Channel()

    override suspend fun isAvailable(): Boolean = CommonBuildKonfig.OPENAI_API_KEY != null

    override suspend fun transcribe(
        audioStreamFrames: Flow<ByteArray>?,
        sampleRate: Int,
        language: STTLanguage,
        conversationContext: STTConversationContext?,
        dictionaryContext: List<String>?,
        contentContext: String?,
        encoding: AudioEncoding,
        initialTimeout: Duration?,
    ): Flow<TranscriptionSessionStatus> = flow {
        if (audioStreamFrames == null) {
            return@flow
        }
        val apiKey = CommonBuildKonfig.OPENAI_API_KEY
            ?: throw TranscriptionException.TranscriptionServiceUnavailable(modelUsed = MODEL_USED)

        emit(TranscriptionSessionStatus.Open)

        val pcm = Buffer()
        audioStreamFrames.collect { chunk ->
            pcm.write(if (sampleRate != TARGET_SAMPLE_RATE) {
                resamplePcm16(chunk, sampleRate, TARGET_SAMPLE_RATE)
            } else {
                chunk
            })
            if (pcm.size > MAX_AUDIO_BYTES) {
                throw TranscriptionException.TranscriptionServiceError(
                    "Audio exceeds OpenAI file size limit (25MB)",
                    modelUsed = MODEL_USED,
                )
            }
        }

        val pcmBytes = pcm.readByteArray()
        if (pcmBytes.isEmpty()) {
            throw TranscriptionException.NoSpeechDetected("empty_audio", modelUsed = MODEL_USED)
        }

        val wavBytes = buildWav(pcmBytes, TARGET_SAMPLE_RATE)
        val languageParam = (language as? STTLanguage.Specific)?.languageCodes?.firstOrNull()

        try {
            val response = client.post(TRANSCRIBE_URL) {
                bearerAuth(apiKey)
                setBody(MultiPartFormDataContent(formData {
                    append("model", MODEL)
                    languageParam?.let { append("language", it) }
                    append("file", wavBytes, Headers.build {
                        append(HttpHeaders.ContentType, "audio/wav")
                        append(HttpHeaders.ContentDisposition, "filename=\"audio.wav\"")
                    })
                }))
            }
            if (!response.status.isSuccess()) {
                throw TranscriptionException.TranscriptionServiceError(
                    "OpenAI returned ${response.status}",
                    modelUsed = MODEL_USED,
                )
            }
            val text = response.body<OpenAITranscriptionResponse>().text?.trim()
            if (text.isNullOrBlank()) {
                throw TranscriptionException.NoSpeechDetected("no_transcript", modelUsed = MODEL_USED)
            }
            emit(TranscriptionSessionStatus.Transcription(text, MODEL_USED))
        } catch (e: TranscriptionException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.e(e) { "OpenAI network error: ${e.message}" }
            throw TranscriptionException.TranscriptionNetworkError(e, modelUsed = MODEL_USED)
        } catch (e: Exception) {
            logger.e(e) { "OpenAI transcription failed: ${e.message}" }
            throw TranscriptionException.TranscriptionServiceError(
                "OpenAI error: ${e.message}",
                cause = e,
                modelUsed = MODEL_USED,
            )
        }
    }
}

internal fun buildWav(pcm: ByteArray, sampleRate: Int): ByteArray =
    Buffer().apply {
        writeWavHeader(sampleRate, pcm.size)
        write(pcm)
    }.readByteArray()

internal fun resamplePcm16(input: ByteArray, inputRate: Int, outputRate: Int): ByteArray {
    if (inputRate == outputRate) return input

    val inputSamples = input.size / 2
    val outputSamples = (inputSamples.toLong() * outputRate / inputRate).toInt()
    val output = ByteArray(outputSamples * 2)

    for (i in 0 until outputSamples) {
        val srcPos = i.toDouble() * (inputSamples - 1) / (outputSamples - 1).coerceAtLeast(1)
        val srcIndex = srcPos.toInt().coerceIn(0, inputSamples - 2)
        val frac = srcPos - srcIndex

        val s0 = readPcm16Sample(input, srcIndex)
        val s1 = readPcm16Sample(input, srcIndex + 1)
        val interpolated = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()

        output[i * 2] = (interpolated.toInt() and 0xFF).toByte()
        output[i * 2 + 1] = (interpolated.toInt() shr 8).toByte()
    }

    return output
}

private fun readPcm16Sample(data: ByteArray, sampleIndex: Int): Double {
    val byteIndex = sampleIndex * 2
    val value = (data[byteIndex].toInt() and 0xFF) or (data[byteIndex + 1].toInt() shl 8)
    return value.toShort().toDouble()
}
