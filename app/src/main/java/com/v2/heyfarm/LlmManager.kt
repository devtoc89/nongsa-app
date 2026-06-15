package com.v2.heyfarm

import android.content.Context
import android.os.ParcelFileDescriptor
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.common.audio.AudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class LlmManager(private val context: Context) {
    private var generativeModel: GenerativeModel? = null
    private var speechRecognizer: com.google.mlkit.genai.speechrecognition.SpeechRecognizer? = null

    init {
        // [스펙 2번] Gemma 4 E4B 설정
        generativeModel = Generation.getClient(
            generationConfig {
                modelConfig = modelConfig {
                    releaseStage = ModelReleaseStage.STABLE
                    preference = ModelPreference.FULL
                }
            }
        )
        
        speechRecognizer = SpeechRecognition.getClient(
            SpeechRecognizerOptions.builder().apply {
                locale = Locale.KOREAN
                preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
            }.build()
        )
    }

    suspend fun checkAndPrepareModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val status = generativeModel?.checkStatus()
            val speechStatus = speechRecognizer?.checkStatus()
            if (status == FeatureStatus.AVAILABLE && speechStatus == FeatureStatus.AVAILABLE) {
                generativeModel?.warmup()
                true
            } else false
        } catch (ignored: Exception) { false }
    }

    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val model = generativeModel ?: return@withContext "모델 미초기화"
        try {
            val request = generateContentRequest(TextPart(prompt)) { this.temperature = 0.2f }
            val response = model.generateContent(request)
            response.candidates.firstOrNull()?.text ?: "응답 없음"
        } catch (e: Exception) { "오류: ${e.localizedMessage}" }
    }

    /**
     * Plan B: 모델 직접 음성 인식 (ASR)
     */
    suspend fun transcribeAudio(audioData: ByteArray): String = withContext(Dispatchers.IO) {
        val recognizer = speechRecognizer ?: return@withContext ""
        try {
            val tempFile = File(context.cacheDir, "temp_audio.raw")
            FileOutputStream(tempFile).use { it.write(audioData) }
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            
            val request = SpeechRecognizerRequest.builder().apply {
                this.audioSource = AudioSource.fromPfd(pfd)
            }.build()
            
            val response = recognizer.startRecognition(request)
                .filterIsInstance<SpeechRecognizerResponse.FinalTextResponse>()
                .first()
            
            pfd.close()
            tempFile.delete()
            response.text
        } catch (e: Exception) { "" }
    }

    fun close() {
        generativeModel?.close()
        speechRecognizer?.close()
    }
}
