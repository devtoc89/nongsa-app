package com.v2.heyfarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.net.Uri
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
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

    // 온디바이스 Nano ASR + AudioRecord 프리롤 캡처(앞 음절 보존). 미가용 시 시스템 STT로 폴백.
    private val useNanoAsr = AtomicBoolean(false)
    private val asrDecided = AtomicBoolean(false)

    // Plan B: Raw Audio Capture
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // 모종 사진 → 서버 비전 판독 → 관측 기록. 카메라(우선) + 갤러리(보조).
    private var cameraImageUri: Uri? = null
    private var pendingDiag = false                 // true면 촬영 후 증상 음성을 받아 사진+음성 진단
    private var pendingDiagPhotoUri: Uri? = null    // 증상 음성 대기 중인 사진
    // 카메라/갤러리 동안 항상-듣기 루프 정지 — 백그라운드 캡처가 사진을 빈 오디오로 소비하는 것 방지.
    @Volatile private var listenPaused = false
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) cameraImageUri?.let { uri ->
            if (pendingDiag) {
                pendingDiag = false; pendingDiagPhotoUri = uri
                listenPaused = false
                speak("사진을 받았어요. 어디가 어떻게 이상한지 말씀해 주세요.")  // 이어서 음성 수신→diag
            } else { listenPaused = false; uploadPhoto(uri) }
        } else {   // 촬영 취소 → 진단 대기 해제하고 듣기 재개
            pendingDiag = false; pendingDiagPhotoUri = null; listenPaused = false; startListening()
        }
    }

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        listenPaused = false
        if (uri != null) uploadPhoto(uri) else startListening()   // 선택 취소 → 듣기 재개
    }

    private fun launchCamera() {
        pauseListening()   // 카메라 동안 백그라운드 캡처 정지(사진 소비 방지)
        val file = File.createTempFile("melon_", ".jpg", cacheDir)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraImageUri = uri
        cameraLauncher.launch(uri)   // non-null Uri 전달
    }

    /** 항상-듣기 루프 정지 + 진행 중 캡처 취소(카메라/갤러리 진입 시). */
    private fun pauseListening() {
        listenPaused = true
        recordingJob?.cancel()
        isListening.set(false)
    }

    /**
     * 업로드 전 JPEG 다운스케일 — 풀해상도(2~3MB)는 Tailscale 업로드·Gemini 비전을 느리게 함.
     * 진단엔 1280px면 충분. 실패 시 원본 바이트 폴백.
     */
    private fun downscaleJpeg(uri: Uri, maxSide: Int = 1280, quality: Int = 85): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val w = bounds.outWidth; val h = bounds.outHeight
            if (w <= 0 || h <= 0) return contentResolver.openInputStream(uri)?.use { it.readBytes() }
            var sample = 1
            while (maxOf(w, h) / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        } catch (e: Exception) {
            try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
        }
    }

    private fun launchCameraForDiag() { pendingDiag = true; launchCamera() }

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
                    val isPlanB by viewModel.isModeB
                    val photos by viewModel.photos
                    val context by viewModel.context

                    // edge-to-edge(targetSDK 36)에서 상태바·내비바에 콘텐츠가 가리지 않게 시스템 인셋 패딩.
                    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        context?.let { ctx ->
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(text = "🌱 ${ctx.cycle} · ${ctx.crop} · ${ctx.stage}" +
                                            (if (ctx.harvest_in > 0) "  ·  수확 D-${ctx.harvest_in}" else ""),
                                         style = MaterialTheme.typography.bodyMedium)
                                    if (ctx.method.isNotBlank())
                                        Text(text = "🪴 " + ctx.method.substringBefore("(").substringBefore(".").trim(),
                                             style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        // 상태 한 줄(듣는 중 / 분석 중 등) — 작게.
                        Text(text = statusText, style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 6.dp))

                        // active 작기 사진 갤러리(있을 때만).
                        if (photos.isNotEmpty()) {
                            LazyRow(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                items(photos) { p ->
                                    AsyncImage(
                                        model = RetrofitClient.BASE_URL + p.photo_url,
                                        contentDescription = p.value ?: "관측 사진",
                                        modifier = Modifier.size(72.dp).padding(end = 6.dp))
                                }
                            }
                        }

                        // ── 대화 말풍선(메인) ── USER/AI 턴만, 오래된→최신, 새 메시지 시 자동 스크롤.
                        val convo = viewModel.log.filter { it.role != ChatRole.DEBUG }.asReversed()
                        val listState = rememberLazyListState()
                        LaunchedEffect(convo.size) {
                            if (convo.isNotEmpty()) listState.animateScrollToItem(convo.size - 1)
                        }
                        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                            items(convo) { e -> MessageBubble(e) }
                        }

                        // ── 하단 액션 바 ──
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly) {
                            Button(onClick = { launchCamera() }) { Text("📷 모종") }
                            Button(onClick = { launchCameraForDiag() }) { Text("📷🎤 진단") }
                            TextButton(onClick = { pauseListening(); photoPickerLauncher.launch("image/*") }) { Text("갤러리") }
                        }

                        // ── 디버그(개발용) — 토글로 펼침. Plan A/B 스위치·원시 로그 포함. ──
                        var showDebug by remember { mutableStateOf(false) }
                        TextButton(onClick = { showDebug = !showDebug }, modifier = Modifier.align(Alignment.Start)) {
                            Text(text = if (showDebug) "🛠 디버그 닫기" else "🛠 디버그",
                                 style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        }
                        if (showDebug) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.align(Alignment.Start).padding(bottom = 4.dp)) {
                                Text(text = if (isPlanB) "🤖 Plan B" else "⚙️ Plan A", style = MaterialTheme.typography.labelMedium)
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(checked = isPlanB, onCheckedChange = { viewModel.toggleMode(it) })
                            }
                            Surface(modifier = Modifier.fillMaxWidth().height(200.dp), color = Color.DarkGray.copy(alpha = 0.1f), shape = MaterialTheme.shapes.medium) {
                                Column(modifier = Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState())) {
                                    viewModel.log.forEach { e ->
                                        Text(text = e.header, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
                                        if (e.content.isNotBlank())
                                            Text(text = e.content, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp), color = MaterialTheme.colorScheme.onSurface)
                                        e.image?.let { img ->
                                            AsyncImage(model = img, contentDescription = null, modifier = Modifier.size(120.dp).padding(vertical = 4.dp))
                                        }
                                    }
                                }
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
        // 음성(audioData)은 전사 후 ViewModel이 사용자 말풍선을 추가 — 여기선 디버그만.
        if (audioData != null) viewModel.addDebugLog("AUDIO", "[음성 입력 캡처]")
        else viewModel.addDebugLog("USER", text)
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
                    val cands = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                    // 사진+음성 진단 대기 중이면, 이번 발화를 증상으로 사진과 함께 전송.
                    val diagUri = pendingDiagPhotoUri
                    if (diagUri != null) {
                        pendingDiagPhotoUri = null
                        val sym = cands.firstOrNull() ?: ""
                        if (sym.isNotEmpty()) uploadDiagPhoto(diagUri, sym) else startListening()
                        return
                    }
                    if (!viewModel.isModeB.value && cands.isNotEmpty()) {
                        // N-best가 여러 개면 NLU가 농업문맥으로 고르게 후보 형태로 전달.
                        val text = if (cands.size > 1)
                            cands.take(3).mapIndexed { i, c -> "후보${i + 1}: $c" }.joinToString(", ", "[", "]")
                        else cands[0]
                        onUserSpeechRecognized(text)
                    } else startListening()
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

    /** 듣기 시작 — 첫 호출 때 Nano ASR 가용성을 판별해 프리롤 캡처 또는 시스템 STT로 분기. */
    private fun startListening() {
        if (isFinishing || isDestroyed || isProcessing.get() || isListening.get() || listenPaused) return
        if (!asrDecided.get()) {
            lifecycleScope.launch {
                val ready = try { viewModel.isSpeechReady() } catch (e: Exception) { false }
                useNanoAsr.set(ready)
                asrDecided.set(true)
                viewModel.addDebugLog("STT", if (ready) "온디바이스 Nano ASR + 프리롤 캡처" else "시스템 STT 폴백(Nano ASR 미가용)")
                routeListen()
            }
            return
        }
        routeListen()
    }

    private fun routeListen() {
        if (useNanoAsr.get()) startNanoCapture() else startSystemStt()
    }

    /**
     * 프리롤 캡처: 비프(발화 신호) **전에** AudioRecord를 먼저 돌려 앞 ~300ms를 버퍼에 담아
     * 첫 음절 짤림을 없앤다. RMS 기반 VAD로 발화 끝(침묵 1초)을 감지해 종료 → Nano 전사.
     */
    private fun startNanoCapture() {
        if (isListening.getAndSet(true)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            isListening.set(false); return
        }
        recordingJob = lifecycleScope.launch(Dispatchers.IO) {
            val bytesPerSec = sampleRate * 2
            val frame = 1280                                   // 40ms @16kHz·16bit·mono
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, channelConfig, audioFormat,
                    maxOf(minBuf, frame * 8))
            } catch (e: Exception) { isListening.set(false); withMain { restartAfter(800) }; return@launch }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release(); isListening.set(false); withMain { restartAfter(800) }; return@launch
            }
            audioRecord = rec
            val out = ByteArrayOutputStream()
            val buf = ByteArray(frame)
            rec.startRecording()

            // 1) 프리롤/워밍업: 비프 전에 ~300ms 미리 캡처(노이즈 플로어도 여기서 측정).
            var warm = 0; var floorSum = 0.0; var floorN = 0
            while (isActive && isListening.get() && warm < bytesPerSec * 300 / 1000) {
                val n = rec.read(buf, 0, buf.size); if (n <= 0) continue
                out.write(buf, 0, n); warm += n
                floorSum += rms(buf, n); floorN++
            }
            val noiseFloor = if (floorN > 0) floorSum / floorN else 300.0
            val threshold = maxOf(noiseFloor * 3.0, 550.0)     // 적응형 발화 임계
            withMain { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100); viewModel.setStatus("말씀하세요...") }

            // 2) VAD 루프: 발화 시작 후 침묵 1s면 종료. 무발화 6s·최대 12s 컷.
            var speechStarted = false; var elapsedMs = 0L; var lastVoiceMs = 0L
            while (isActive && isListening.get()) {
                val n = rec.read(buf, 0, buf.size); if (n <= 0) continue
                out.write(buf, 0, n)
                elapsedMs += (n / 2) * 1000L / sampleRate
                if (rms(buf, n) > threshold) {
                    if (!speechStarted) { speechStarted = true; withMain { viewModel.setStatus("듣는 중...") } }
                    lastVoiceMs = elapsedMs
                }
                if (!speechStarted && elapsedMs > 6000L) break
                if (speechStarted && elapsedMs - lastVoiceMs > 1500L) break   // 중간 호흡을 종료로 오인 완화
                if (elapsedMs > 12000L) break
            }
            try { rec.stop() } catch (e: Exception) {}
            rec.release(); audioRecord = null; isListening.set(false)

            val pcm = out.toByteArray()
            withMain {
                if (!speechStarted || pcm.size < bytesPerSec / 2) {   // 발화 없음(<0.5s) → 재청취
                    viewModel.setStatus("음성 대기 중..."); restartAfter(300); return@withMain
                }
                val diagUri = pendingDiagPhotoUri
                if (diagUri != null) {
                    pendingDiagPhotoUri = null
                    viewModel.setStatus("증상 인식 중...")
                    lifecycleScope.launch {
                        val sym = try { viewModel.transcribe(pcm) } catch (e: Exception) { "" }
                        if (sym.isNotBlank()) uploadDiagPhoto(diagUri, sym) else startListening()
                    }
                } else {
                    // 기존 Plan B 경로 재사용 — ViewModel이 Nano 전사 후 NLU/NLG 수행.
                    onUserSpeechRecognized("", audioData = pcm)
                }
            }
        }
    }

    /** PCM16(LE) 프레임의 RMS 진폭. */
    private fun rms(buf: ByteArray, len: Int): Double {
        var sum = 0.0; var c = 0; var i = 0
        while (i + 1 < len) {
            val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
            sum += s.toDouble() * s; c++; i += 2
        }
        return if (c > 0) kotlin.math.sqrt(sum / c) else 0.0
    }

    private fun restartAfter(ms: Long) {
        if (!isProcessing.get()) lifecycleScope.launch(Dispatchers.Main) { delay(ms); startListening() }
    }

    private suspend fun withMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    /** 폴백: 시스템 SpeechRecognizer 기반 듣기(Nano ASR 미가용 기기). */
    private fun startSystemStt() {
        if (isFinishing || isDestroyed || isProcessing.get()) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)   // 온라인 인식 강제(한국어 정확도↑)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)          // N-best → NLU가 농업문맥으로 보정
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            // 짧은 발화가 너무 빨리 끊겨 NO_MATCH 나는 것 완화 — 최소 인식 길이·관용 침묵 늘림.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
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

    private fun uploadPhoto(uri: Uri) {
        if (!isProcessing.compareAndSet(false, true)) return
        viewModel.setStatus("사진 분석 중...")
        viewModel.addImageLog("USER", uri, "[사진] 업로드")
        lifecycleScope.launch(Dispatchers.IO) {
            val msg = try {
                val bytes = downscaleJpeg(uri)
                if (bytes == null) "사진을 읽지 못했습니다." else {
                    val reqBody = bytes.toRequestBody("image/jpeg".toMediaType())
                    val part = MultipartBody.Part.createFormData("image", "photo.jpg", reqBody)
                    val res = RetrofitClient.api.observePhoto(part).body()
                    if (res?.recorded == true) "모종 사진을 관측으로 기록했습니다."
                    else (res?.reason ?: "사진을 판독하지 못했습니다.")
                }
            } catch (e: Exception) { "사진 업로드 실패: ${e.localizedMessage}" }
            withContext(Dispatchers.Main) { viewModel.addDebugLog("AI", msg); viewModel.recordTurn("모종 사진 업로드", msg); viewModel.refreshPhotos(); speak(msg) }
        }
    }

    private fun uploadDiagPhoto(uri: Uri, symptom: String) {
        if (!isProcessing.compareAndSet(false, true)) return
        viewModel.setStatus("사진+음성 진단 중...")
        viewModel.addImageLog("USER", uri, "[사진+음성] $symptom")
        lifecycleScope.launch(Dispatchers.IO) {
            val msg = try {
                val bytes = downscaleJpeg(uri)
                if (bytes == null) "사진을 읽지 못했습니다." else {
                    val imgPart = MultipartBody.Part.createFormData(
                        "image", "photo.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
                    val symPart = symptom.toRequestBody("text/plain; charset=utf-8".toMediaType())
                    val res = RetrofitClient.api.diagPhoto(imgPart, symPart).body()
                    if (res != null) {
                        val badge = if (res.grounded) "🟢 KMS 근거" else "🟡 사진 추정"
                        "$badge — ${res.disease}. ${res.treatment}"
                    } else "진단에 실패했습니다."
                }
            } catch (e: Exception) { "진단 실패: ${e.localizedMessage}" }
            withContext(Dispatchers.Main) { viewModel.addDebugLog("AI", msg); viewModel.recordTurn("사진+음성 진단: $symptom", msg); viewModel.refreshPhotos(); speak(msg) }
        }
    }

    private fun speak(text: String) {
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "v1")
        textToSpeech?.speak(stripMarkdown(text), TextToSpeech.QUEUE_FLUSH, params, "v1")
    }

    /** TTS가 마크다운 기호(###, **, 목록·코드펜스)를 그대로 읽지 않게 평문화. 소스 불문 차단. */
    private fun stripMarkdown(text: String): String {
        return text
            .replace(Regex("```[a-zA-Z]*"), "")          // 코드펜스
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "") // 헤더 ###
            .replace(Regex("\\*\\*|__|`"), "")            // 굵게/인라인코드
            .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")     // 불릿
            .replace(Regex("(?m)^\\s*>\\s*"), "")         // 인용
            .replace(Regex("\\s+"), " ")                   // 줄바꿈·중복공백 정리
            .trim()
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

/** 대화 말풍선 — USER는 오른쪽(primary), AI는 왼쪽(surfaceVariant). 이미지 포함 가능. */
@Composable
private fun MessageBubble(e: LogEntry) {
    val isUser = e.role == ChatRole.USER
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (e.content.isNotBlank())
                    Text(text = e.content, style = MaterialTheme.typography.bodyLarge,
                         color = MaterialTheme.colorScheme.onSurface)
                e.image?.let { img ->
                    AsyncImage(model = img, contentDescription = null,
                        modifier = Modifier.size(200.dp).padding(top = if (e.content.isNotBlank()) 6.dp else 0.dp))
                }
            }
        }
    }
}
