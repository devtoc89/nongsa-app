package com.v2.heyfarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v2.heyfarm.ui.theme.HeyfarmTheme
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var viewModel: AssistantViewModel
    private var textToSpeech: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var toneGenerator: ToneGenerator? = null
    
    private val isListening = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)
    private var lastPartialText = ""

    // Plan B: Raw Audio Capture
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) initializeApp() else {
            Toast.makeText(this, "마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            initializeApp()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun initializeApp() {
        textToSpeech = TextToSpeech(this, this)
        toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        
        setContent {
            viewModel = viewModel()
            HeyfarmTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val statusText by viewModel.statusText
                    val debugLog by viewModel.debugLog
                    val isPlanB by viewModel.isModeB

                    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                            Text(text = if (isPlanB) "🤖 Plan B (모델 직접 인식)" else "⚙️ Plan A (시스템 STT)")
                            Spacer(modifier = Modifier.width(16.dp))
                            Switch(checked = isPlanB, onCheckedChange = { viewModel.toggleMode(it) })
                        }
                        Text(text = statusText, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
                        HorizontalDivider(thickness = 1.dp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "[Debug Log]", style = MaterialTheme.typography.labelLarge, color = Color.Gray, modifier = Modifier.align(Alignment.Start))
                        Surface(modifier = Modifier.fillMaxWidth().weight(1f), color = Color.DarkGray.copy(alpha = 0.1f), shape = MaterialTheme.shapes.medium) {
                            Column(modifier = Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState())) {
                                Text(text = debugLog, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp), color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
        initSpeechRecognizer()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val query = intent?.getStringExtra("query") ?: intent?.data?.getQueryParameter("query")
        if (!query.isNullOrEmpty()) onUserSpeechRecognized(query)
    }

    private fun onUserSpeechRecognized(text: String, audioData: ByteArray? = null) {
        if (!isProcessing.compareAndSet(false, true)) return
        viewModel.addDebugLog("USER", if (audioData != null) "[Plan B Audio Captured]" else text)
        viewModel.processQuery(text, audioData = audioData, 
            onSpeak = { response -> speak(response) }, 
            onProcessingFinished = { isProcessing.set(false) })
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening.set(true)
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                    viewModel.setStatus("듣고 있습니다...")
                    if (viewModel.isModeB.value) startRecordingForPlanB()
                }
                override fun onEndOfSpeech() { isListening.set(false) }
                override fun onError(error: Int) {
                    isListening.set(false)
                    recordingJob?.cancel()
                    if (!isProcessing.get()) lifecycleScope.launch { delay(1500); startListening() }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (!viewModel.isModeB.value && text.isNotEmpty()) onUserSpeechRecognized(text) else startListening()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val currentText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
                    if (currentText.length > lastPartialText.length) lastPartialText = currentText
                    viewModel.setStatus("듣는 중: $currentText")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startRecordingForPlanB() {
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, channelConfig, audioFormat, minBufferSize)
        val audioDataStream = ByteArrayOutputStream()
        
        audioRecord?.startRecording()
        recordingJob = lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(minBufferSize)
            while (isActive && isListening.get()) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) audioDataStream.write(buffer, 0, read)
            }
            audioRecord?.stop()
            audioRecord?.release()
            
            val finalAudioData = audioDataStream.toByteArray()
            if (viewModel.isModeB.value && finalAudioData.isNotEmpty()) {
                withContext(Dispatchers.Main) { onUserSpeechRecognized("", audioData = finalAudioData) }
            }
        }
    }

    private fun startListening() {
        if (isFinishing || isDestroyed || isProcessing.get()) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        }
        lifecycleScope.launch(Dispatchers.Main) {
            try { 
                if (!isListening.get()) {
                    delay(100) // 마이크 초기화 대기
                    speechRecognizer?.startListening(intent) 
                }
            } catch (ignored: Exception) { delay(1000); startListening() }
        }
    }

    private fun speak(text: String) {
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "v1")
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "v1")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.KOREAN
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { isProcessing.set(true) }
                override fun onDone(utteranceId: String?) { 
                    isProcessing.set(false)
                    if (utteranceId != "exit_tts") {
                        lifecycleScope.launch(Dispatchers.Main) { delay(1000); startListening() }
                    }
                }
                override fun onError(utteranceId: String?) { 
                    Log.e("MainActivity", "TTS Error: $utteranceId")
                    isProcessing.set(false) 
                }
            })
            speak("Hey Farm이 준비되었습니다.")
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
        toneGenerator?.release()
        recordingJob?.cancel()
        super.onDestroy()
    }
}
