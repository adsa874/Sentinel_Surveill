package com.sentinel.network

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

// API Request/Response models
data class EventRequest(
    @SerializedName("type") val type: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("track_id") val trackId: Int,
    @SerializedName("employee_id") val employeeId: String? = null,
    @SerializedName("license_plate") val licensePlate: String? = null,
    @SerializedName("duration") val duration: Long = 0,
    @SerializedName("device_id") val deviceId: String
)

data class BatchEventRequest(
    @SerializedName("events") val events: List<EventRequest>,
    @SerializedName("device_id") val deviceId: String
)

data class BatchEventResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("processed") val processed: Int,
    @SerializedName("message") val message: String? = null
)

data class EmployeeResponse(
    @SerializedName("employee_id") val employeeId: String,
    @SerializedName("name") val name: String,
    @SerializedName("department") val department: String?,
    @SerializedName("face_embedding") val faceEmbedding: List<Float>?
)

data class EmployeeListResponse(
    @SerializedName("employees") val employees: List<EmployeeResponse>
)

data class DeviceRegistration(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("model") val model: String,
    @SerializedName("os_version") val osVersion: String
)

data class DeviceRegistrationResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("api_key") val apiKey: String,
    @SerializedName("message") val message: String? = null
)

interface SentinelApiService {
    @POST("api/events")
    suspend fun sendEvents(
        @Header("X-API-Key") apiKey: String,
        @Body request: BatchEventRequest
    ): Response<BatchEventResponse>

    @GET("api/employees")
    suspend fun getEmployees(
        @Header("X-API-Key") apiKey: String
    ): Response<EmployeeListResponse>

    @POST("api/devices/register")
    suspend fun registerDevice(
        @Body request: DeviceRegistration
    ): Response<DeviceRegistrationResponse>

    @GET("api/health")
    suspend fun healthCheck(): Response<Map<String, Any>>
}

object SentinelApi {
    private const val DEFAULT_BASE_URL = "http://10.0.2.2:8000/" // Android emulator localhost
    private var baseUrl: String = DEFAULT_BASE_URL
    private var retrofit: Retrofit? = null

    fun configure(url: String) {
        baseUrl = url
        retrofit = null // Force rebuild
    }

    private fun getRetrofit(): Retrofit {
        return retrofit ?: synchronized(this) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .also { retrofit = it }
        }
    }

    val service: SentinelApiService
        get() = getRetrofit().create(SentinelApiService::class.java)
}
