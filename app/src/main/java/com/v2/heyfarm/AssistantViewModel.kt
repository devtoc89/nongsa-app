package com.v2.heyfarm

import android.app.Application
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.v2.heyfarm.prompt.AssistantPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val gson = Gson()
    private val llmManager = LlmManager(application)

    private val _statusText = mutableStateOf("Hey Farm 서비스가 준비되었습니다.")
    val statusText: State<String> = _statusText

    // 대화/디버그 로그 — 텍스트 또는 이미지(사진) 항목. 최신이 위.
    val log = mutableStateListOf<LogEntry>()

    private val _isModeB = mutableStateOf(false)
    val isModeB: State<Boolean> = _isModeB

    private val chatHistory = mutableListOf<String>()

    private val _photos = mutableStateOf<List<PhotoItem>>(emptyList())
    val photos: State<List<PhotoItem>> = _photos

    private val _context = mutableStateOf<ContextResp?>(null)
    val context: State<ContextResp?> = _context

    init {
        // 앱 시작 시 서버에서 최신 프롬프트(NLU/NLG) 수신 — 실패 시 폴백 유지.
        viewModelScope.launch { AssistantPrompt.refresh() }
        refreshPhotos()
        refreshContext()
    }

    /** active 작기 컨텍스트 갱신(작기·작물·단계·수확D-day). */
    fun refreshContext() {
        viewModelScope.launch {
            try { _context.value = withContext(Dispatchers.IO) { RetrofitClient.api.getContext().body() } }
            catch (_: Exception) {}
        }
    }

    /** active 작기 사진 갤러리 갱신(업로드 후·시작 시). */
    fun refreshPhotos() {
        viewModelScope.launch {
            try { _photos.value = withContext(Dispatchers.IO) { RetrofitClient.api.getPhotos().body() } ?: emptyList() }
            catch (_: Exception) {}
        }
    }

    fun setStatus(text: String) {
        _statusText.value = text
    }

    /** 온디바이스 Nano ASR 가용 여부(프리롤 캡처 경로 선택용). */
    suspend fun isSpeechReady(): Boolean = llmManager.isSpeechReady()

    /** 캡처 PCM을 온디바이스 Nano로 전사(사진+음성 진단 등 텍스트가 즉시 필요한 경우). */
    suspend fun transcribe(audio: ByteArray): String =
        withContext(Dispatchers.IO) { llmManager.transcribeAudio(audio) }

    private fun header(tag: String): String {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val prefix = when (tag) {
            "USER" -> "👤 [나]"; "AI" -> "🤖 [AI]"; "API" -> "⚙️ [API]"; "NLU" -> "🔍 [NLU]"
            else -> "⚙️ [$tag]"
        }
        return "$prefix ($ts)"
    }

    fun addDebugLog(tag: String, content: String) { log.add(0, LogEntry(header(tag), content, null, roleOf(tag))) }

    /** 사진(이미지) 로그 — 대화창에 인라인 표시. image=로컬 Uri 또는 URL. */
    fun addImageLog(tag: String, image: Any?, content: String = "") { log.add(0, LogEntry(header(tag), content, image, roleOf(tag))) }

    private fun roleOf(tag: String): ChatRole = when (tag) {
        "USER" -> ChatRole.USER; "AI" -> ChatRole.AI; else -> ChatRole.DEBUG
    }

    fun toggleMode(enabled: Boolean) {
        _isModeB.value = enabled
    }

    fun processQuery(userInput: String, audioData: ByteArray? = null, onSpeak: (String) -> Unit, onProcessingFinished: () -> Unit) {
        viewModelScope.launch {
            try {
                processCombinedFlow(userInput, audioData, onSpeak)
            } finally {
                onProcessingFinished()
            }
        }
    }

    private suspend fun processCombinedFlow(userInput: String, audioData: ByteArray?, onSpeak: (String) -> Unit) {
        val isDirect = audioData != null
        _statusText.value = if (isDirect) "모델이 직접 음성 분석 중 (Plan B)..." else "AI가 생각 중 (Plan A)..."
        
        if (!llmManager.checkAndPrepareModel()) {
            onSpeak("AI 모델이 준비되지 않았습니다.")
            return
        }

        // 1. 공통 데이터 싱크 (농장 현황 파악)
        val syncResponse = withContext(Dispatchers.IO) {
            try { 
                val res = RetrofitClient.api.getFarmSync().body()
                addDebugLog("API", "SYNC: ${gson.toJson(res)}")
                res
            } catch (e: Exception) { 
                addDebugLog("API", "SYNC ERROR: ${e.localizedMessage}")
                null 
            }
        }

        // 2. [핵심 NLU] 의도 추출 및 히스토리 참조
        val historyContext = chatHistory.takeLast(10).joinToString("\n")
        
        val nluResultRaw = withContext(Dispatchers.IO) {
            if (isDirect && audioData != null) {
                // 온디바이스 Nano ASR(프리롤 캡처 오디오) 전사
                llmManager.transcribeAudio(audioData)
            } else {
                // Plan A: 시스템 STT 텍스트 분석
                val nluPrompt = AssistantPrompt.getCombinedNluPrompt(historyContext, userInput)
                addDebugLog("NLU PROMPT", nluPrompt)
                llmManager.generate(nluPrompt)
            }
        }

        // 원시 STT(음성 직접 인식 시 Nano 전사 결과) — 텔레메트리 transcript로 기록.
        val rawStt = if (isDirect) nluResultRaw else userInput

        // 빈 전사/오인식 차단 — 빈 텍스트를 NLU에 넣으면 엉뚱한 답이 나오므로 재청취 유도.
        if (isDirect && rawStt.isBlank()) {
            addDebugLog("STT", "전사 실패(빈 결과) — 재청취")
            onSpeak("죄송해요, 잘 못 알아들었어요. 다시 한번 말씀해 주세요.")
            return
        }

        var finalTranscription = if (isDirect) nluResultRaw else userInput
        val finalNluJson = if (isDirect) {
            // Plan B에서 텍스트를 뽑았으므로, 다시 의도 파악 수행
            val nluPrompt = AssistantPrompt.getCombinedNluPrompt(historyContext, finalTranscription)
            llmManager.generate(nluPrompt)
        } else {
            nluResultRaw
        }

        val nluJson = extractJson(finalNluJson)
        addDebugLog("NLU Result", nluJson)

        val results = mutableMapOf<String, Any?>()
        var loggedIntents: List<String> = emptyList()
        try {
            val intentData = gson.fromJson(nluJson, IntentData::class.java)
            loggedIntents = intentData.intents ?: emptyList()
            if (!intentData.transcription.isNullOrBlank()) {
                finalTranscription = intentData.transcription
            }
            
            // 3. 각 의도별 API 병렬 실행
            withContext(Dispatchers.IO) {
                val safeIntents = intentData.intents ?: emptyList()
                val deferreds = safeIntents.map { intent ->
                    async {
                        try {
                            when (intent) {
                                "WORK_REPORT" -> {
                                    val report = WorkReport(intentData.action ?: "", intentData.zone ?: "", "COMPLETED")
                                    val res = RetrofitClient.api.reportWork(report).body()
                                    results["work_report"] = res
                                    addDebugLog("API", "WORK_REPORT: ${gson.toJson(res)}")
                                }
                                "DIAGNOSIS" -> {
                                    val res = RetrofitClient.api.diagnoseSymptom(SymptomRequest(intentData.symptom ?: "")).body()
                                    results["diagnosis"] = res
                                    addDebugLog("API", "DIAGNOSIS: ${gson.toJson(res)}")
                                }
                                "MANUAL_LOOKUP" -> {
                                    val res = RetrofitClient.api.getMelonManual().body()
                                    results["manual"] = res
                                    addDebugLog("API", "MANUAL: ${gson.toJson(res)}")
                                }
                                "STATUS_CHECK" -> {
                                    val res = RetrofitClient.api.getFacilityStatus().body()
                                    results["facility_status"] = res
                                    addDebugLog("API", "STATUS: ${gson.toJson(res)}")
                                }
                                "INVENTORY_CHECK" -> {
                                    val res = RetrofitClient.api.getInventory().body()
                                    results["inventory"] = res
                                    addDebugLog("API", "INVENTORY: ${gson.toJson(res)}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ViewModel", "API Error: $intent", e)
                        }
                    }
                }
                deferreds.awaitAll()
            }
        } catch (e: Exception) {
            Log.e("ViewModel", "Parsing Error", e)
        }

        // 음성 입력의 사용자 말풍선 — 전사/NLU로 확정된 텍스트로 표시(원시 "[음성 캡처]" 대신).
        if (isDirect) addDebugLog("USER", finalTranscription)

        // 4. [핵심 NLG] 통합 답변 생성 (세부 지침 엄수)
        val syncResponseJson = gson.toJson(syncResponse)
        val resultsJson = gson.toJson(results)
        val nlgPrompt = AssistantPrompt.getCombinedNlgPrompt(historyContext, syncResponseJson, resultsJson, finalTranscription)
        
        addDebugLog("NLG PROMPT", nlgPrompt)
        
        val finalResponse = withContext(Dispatchers.IO) { 
            llmManager.generate(nlgPrompt) 
        }

        chatHistory.add("User: $finalTranscription")
        chatHistory.add("AI: $finalResponse")
        if (chatHistory.size > 10) repeat(2) { chatHistory.removeAt(0) }

        addDebugLog("AI", finalResponse)
        onSpeak(finalResponse)
        sendTelemetry(rawStt, finalTranscription, loggedIntents, results.keys.toList(), finalResponse)
        // 작업 보고·날짜 변경으로 단계/수확 D-day가 바뀔 수 있어 매 턴 후 헤더 갱신.
        refreshContext()
    }

    /** 한 음성 턴을 서버(Langfuse)로 전송 — 실패 무시(대화 흐름에 영향 없음). */
    private fun sendTelemetry(transcript: String, corrected: String, intents: List<String>, apis: List<String>, response: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RetrofitClient.api.telemetry(TelemetryReq(
                    transcript = transcript, corrected = corrected,
                    intent = intents.joinToString(","), apis = apis,
                    response = response, device = android.os.Build.MODEL,
                    turn_id = java.util.UUID.randomUUID().toString()))
            } catch (_: Exception) {}
        }
    }

    private fun extractJson(text: String): String {
        val regex = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL)
        return regex.find(text)?.value ?: "{}"
    }

    override fun onCleared() {
        super.onCleared()
        llmManager.close()
    }

    data class IntentData(val transcription: String?, val intents: List<String>?, val zone: String?, val action: String?, val value: String?, val symptom: String?)
}

/** 대화 말풍선 역할 — USER/AI는 대화 뷰에, DEBUG는 디버그 패널에만 표시. */
enum class ChatRole { USER, AI, DEBUG }

/** 대화/디버그 로그 항목. role로 대화(USER/AI) vs 디버그 구분. image!=null 이면 이미지 표시. */
data class LogEntry(val header: String, val content: String, val image: Any? = null, val role: ChatRole = ChatRole.DEBUG)
