package com.example.wardroberec.api

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface WardrobeApi {

    @Multipart
    @POST("clothes")
    suspend fun uploadClothing(
        @Part("category") category: RequestBody,
        @Part("color") color: RequestBody,
        @Part("material") material: RequestBody,
        @Part("occasion") occasion: RequestBody,
        @Part image: MultipartBody.Part
    ): Response<ClothingResponse>

    @POST("recommendation")
    suspend fun postRecommendation(
        @Body request: RecommendationRequest
    ): Response<RecommendationResponse>


    @GET("recommendation")
    suspend fun getRecommendation(
        @Query("occasion") occasion: String,
        @Query("category") category: String? = null,
        @Query("color") color: String? = null,
        @Query("material") material: String? = null,
        @Query("keywords") keywords: List<String>? = null // Retrofit will handle multiple query params
    ): Response<RecommendationResponse>


    @GET("clothes")
    suspend fun getClothes(
        @Query("occasion") occasion: String? = null,
        @Query("preferences") preferences: String? = null
    ): Response<List<ClothingItem>>

    @DELETE("clothes/{id}")
    suspend fun deleteClothing(
        @Path("id") id: Int
    ): Response<Unit>

    @POST("rate")
    suspend fun postRating(@Body request: RatingRequest): Response<Unit>
}

data class ClothingResponse(
    val message: String,
    val id: Int
)

data class RecommendationRequest(
    val occasion: String,
    val category: String? = null,
    val color: String? = null,
    val material: String? = null,
    val keywords: List<String>? = null
)

data class OutfitItem(
    val id: Int,
    val color: String,
    val material: String,
    val imageUrl: String,
    val labels: List<String>?
)

data class RecommendationResponse(
    val outfitId: String,   // <-- NEW
    val weather: String,
    val occasion: String,
    val items: Map<String, OutfitItem?>
)

data class ClothingItem(
    val id: Int,
    val category: String,
    val color: String,
    val material: String,
    val occasion: String,
    @SerializedName("image_url") val imageUrl: String
)

data class RatingRequest(
    @SerializedName("outfit_id") val outfitId: String,
    val rating: Int,
    val items: Map<String, Int>? = null,
    @SerializedName("user_id") val userId: String? = null
)



