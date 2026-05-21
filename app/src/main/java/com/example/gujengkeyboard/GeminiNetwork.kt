package com.example.gujengkeyboard

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

// --- Data Models ---
data class GeminiRequest(val contents: List<Content>)
data class Content(val parts: List<Part>)
data class Part(val text: String)

data class GeminiResponse(val candidates: List<Candidate>?)
data class Candidate(val content: Content?)

// --- API Interface ---
interface GeminiApi {
    // 🚀 Using @Url to pass the complete, exact URL
    @POST
    suspend fun generateResponse(
        @Url fullUrl: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

// --- Retrofit Client ---
object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    val instance: GeminiApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GeminiApi::class.java)
    }
}