package com.v2.heyfarm

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface HeyFarmApi {
    /** 1. 지식 그래프 진단 */
    @POST("/api/v1/diag/symptom")
    suspend fun diagnoseSymptom(@Body request: SymptomRequest): Response<DiagnosisResponse>

    /** 2. 생육 매뉴얼 조회 */
    @GET("/api/v1/manual/melon")
    suspend fun getMelonManual(): Response<ManualData>

    /** 3. 환경 정보 조회 */
    @GET("/api/v1/facility/status")
    suspend fun getFacilityStatus(): Response<FacilityStatus>

    /** 4. 재고 현황 조회 */
    @GET("/api/v1/farm/inventory")
    suspend fun getInventory(): Response<List<InventoryItem>>

    /** 5. 작업 결과 보고 */
    @POST("/api/v1/work/report")
    suspend fun reportWork(@Body report: WorkReport): Response<WorkReportResponse>

    /** 6. 전체 데이터 동기화 */
    @GET("/api/v1/farm/sync")
    suspend fun getFarmSync(): Response<FarmSyncResponse>

    /** 7. 온디바이스 Nano 프롬프트(NLU/NLG)를 서버에서 수신 — 앱 리빌드 없이 튜닝 */
    @GET("/api/v1/prompts")
    suspend fun getPrompts(): Response<PromptConfig>
}

// --- Request/Response Data Models ---

data class SymptomRequest(val symptom: String)

data class DiagnosisResponse(
    val disease: String,
    val treatment: String,
    val caution: String
)

data class ManualData(
    val crop: String,
    val stage: String,
    val guide: String
)

data class FacilityStatus(
    val temp: Double,
    val humidity: Int,
    val soil_moisture: String,
    val weather: String
)

data class InventoryItem(
    val item: String,
    val qty: Int,
    val unit: String
)

data class WorkReport(
    val action: String,
    val zone: String,
    val status: String
)

data class WorkReportResponse(
    val status: String,
    val message: String
)

data class FarmSyncResponse(
    val manual: ManualData?,
    val knowledge: List<DiagnosisResponse>?,
    val status: FacilityStatus?,
    val inventory: List<InventoryItem>?
)

// 서버가 통제하는 온디바이스 Nano 프롬프트(자리표시자 {{...}}는 앱이 채움). intents/params는 추가 필드(무시 가능).
data class PromptConfig(
    val version: String = "",
    val nlu: String = "",
    val nlg: String = ""
)
