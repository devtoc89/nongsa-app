package com.v2.heyfarm

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

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

    /** 8. 모종 사진 업로드 → 서버 비전 판독 → active 작기 관측 기록 */
    @Multipart
    @POST("/api/v1/observe-photo")
    suspend fun observePhoto(@Part image: MultipartBody.Part): Response<PhotoObsResponse>

    /** 9. 사진 + 음성 증상설명 → 멀티모달 비전 진단 */
    @Multipart
    @POST("/api/v1/diag/photo")
    suspend fun diagPhoto(@Part image: MultipartBody.Part,
                          @Part("symptom") symptom: RequestBody): Response<DiagnosisResponse>
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

// 사진 관측 응답(서버 비전 판독). assessment는 가변 구조라 Map으로 수신.
data class PhotoObsResponse(
    val recorded: Boolean = false,
    val reason: String? = null,
    val observation_id: String? = null,
    val metric: String? = null,
    val value: String? = null,
    val assessment: Map<String, Any?>? = null
)
