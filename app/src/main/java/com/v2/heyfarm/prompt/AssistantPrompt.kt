package com.v2.heyfarm.prompt

import com.v2.heyfarm.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 온디바이스 Nano 프롬프트 — **서버(melon_kms)에서 수신**해 사용(앱 리빌드 없이 튜닝).
 * 앱은 자리표시자 {{...}}만 채운다. 서버 미연결 시 아래 기본값(폴백)으로 동작.
 * refresh()를 앱 시작 시 1회 호출하면 최신 프롬프트를 받아 캐시한다.
 */
object AssistantPrompt {

    @Volatile private var nluTemplate: String = DEFAULT_NLU
    @Volatile private var nlgTemplate: String = DEFAULT_NLG
    @Volatile var version: String = "local-fallback"; private set

    /** 서버에서 최신 프롬프트 수신(실패 시 현재/폴백 유지). */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        try {
            val r = RetrofitClient.api.getPrompts().body() ?: return@withContext
            if (r.nlu.isNotBlank()) nluTemplate = r.nlu
            if (r.nlg.isNotBlank()) nlgTemplate = r.nlg
            if (r.version.isNotBlank()) version = r.version
        } catch (_: Exception) { /* 오프라인 등 — 기존/폴백 유지 */ }
    }

    fun getCombinedNluPrompt(historyContext: String, userInput: String): String =
        nluTemplate.replace("{{history}}", historyContext).replace("{{user_input}}", userInput)

    fun getCombinedNlgPrompt(historyContext: String, syncResponseJson: String,
                             resultsJson: String, recognizedText: String): String =
        nlgTemplate.replace("{{history}}", historyContext)
            .replace("{{sync}}", syncResponseJson)
            .replace("{{results}}", resultsJson)
            .replace("{{recognized}}", recognizedText)

    // ---- 오프라인 폴백(서버 nlu/nlg와 동일 — 자리표시자 {{...}}) ----
    private val DEFAULT_NLU = """
<start_of_turn>user
너는 스마트팜 'Hey Farm'의 데이터 추출 엔진이야.
사용자의 음성 인식(STT) 결과를 분석해 의도를 파악하고 JSON으로만 출력해.

[핵심 지침]:
1. 의도(intents)를 [의도 판별 기준]에 따라 1개 이상 배열에 담아. 해당 없으면 빈 배열 [].
2. JSON 형식 외에 다른 말은 절대 하지 마(설명·코드블록 금지).
3. STT 후보가 여럿이면 농업 문맥에 가장 알맞은 한 문장을 'transcription'에.
4. 뭉개진 발음을 올바른 농업 용어로 보정(양핵→양액, 관쥬→관주, 진드물→진딧물, 방재→방제).

[의도 판별 기준]:
- DIAGNOSIS: 병·증상·이상 호소 → symptom에 증상 요약.
- WORK_REPORT: 완료한 작업 보고 → action, zone 추출.
- MANUAL_LOOKUP: 지금 할 일·재배법·단계 질문.
- STATUS_CHECK: 환경·상태 질문.
- INVENTORY_CHECK: 재고·자재 질문.
- 인사·잡담이면 intents=[].

[예시]
입력: "잎에 흰 가루가 생겼어요"
출력: {"transcription":"잎에 흰 가루가 생겼어요","intents":["DIAGNOSIS"],"zone":"","action":"","value":"","symptom":"잎에 흰 가루"}
입력: "오늘 가운데 줄에 물 줬어"
출력: {"transcription":"오늘 가운데 줄에 물 줬어","intents":["WORK_REPORT"],"zone":"가운데 줄","action":"물주기","value":"","symptom":""}
입력: "지금 뭐 해야 돼?"
출력: {"transcription":"지금 뭐 해야 돼?","intents":["MANUAL_LOOKUP"],"zone":"","action":"","value":"","symptom":""}

[이전 대화]:
{{history}}

사용자 질문(STT 결과): {{user_input}}

출력(JSON만): <end_of_turn>
<start_of_turn>model
""".trimIndent()

    private val DEFAULT_NLG = """
<start_of_turn>user
너는 스마트팜 'Hey Farm'의 전문 영농 비서야. 아래 [처리 결과]를 바탕으로 답해.

[농장 데이터]: {{sync}}
[처리 결과]: {{results}}
[이전 대화]: {{history}}
[현재 질문]: {{recognized}}

지침:
1. 현재 질문에 대한 답만, 길게 말하지 마. 더 할 말 있으면 사용자가 원하는지 물어봐.
2. [처리 결과] 안의 수치·사실만 근거로 말하고, 없는 값(특히 약제·수치)은 지어내지 마.
3. TTS용이니 읽기 편한 존댓말로 짧고 명확하게. 인사 반복 금지.<end_of_turn>
<start_of_turn>model
""".trimIndent()
}
