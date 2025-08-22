package com.example.wardroberec

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.example.wardroberec.api.ClothingItem
import com.example.wardroberec.api.RatingRequest
import com.example.wardroberec.api.RetrofitInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.wardroberec.api.RecommendationRequest
import com.example.wardroberec.api.RecommendationResponse
import com.example.wardroberec.api.RetrofitInstance.api
import com.google.gson.Gson
import kotlinx.coroutines.launch


class OutfitViewModel : ViewModel() {

    var recommendation by mutableStateOf<RecommendationResponse?>(null)
        private set

    var selectedCategory by mutableStateOf("")
        private set
    var selectedColor by mutableStateOf("")
        private set
    var selectedMaterial by mutableStateOf("")
        private set
    var selectedOccasion by mutableStateOf("")
        private set
    var selectedKeywords by mutableStateOf<List<String>>(emptyList())
        private set

    fun setPreferences(
        category: String,
        color: String,
        material: String,
        occasion: String,
        keywords: List<String> = emptyList()
    ) {
        selectedCategory = category
        selectedColor = color
        selectedMaterial = material
        selectedOccasion = occasion
        selectedKeywords = keywords
    }

    suspend fun fetchRecommendation() {
        withContext(Dispatchers.IO) {
            try {
                val request = RecommendationRequest(
                    occasion = selectedOccasion,
                    category = selectedCategory.takeIf { it.isNotBlank() },
                    color = selectedColor.takeIf { it.isNotBlank() },
                    material = selectedMaterial.takeIf { it.isNotBlank() },
                    keywords = selectedKeywords.takeIf { it.isNotEmpty() }
                )

                val response = RetrofitInstance.api.postRecommendation(request)
                if (response.isSuccessful) {
                    val rawJson = response.errorBody()?.string() ?: response.body().toString()
                    Log.d("Recommendation", "RAW SERVER RESPONSE: $rawJson")
                    val body = response.body()
                    Log.d("Recommendation", "RAW JSON: $body")
                    recommendation = body
                    val items = body?.items ?: emptyMap()
                    Log.d("Recommendation", "Fetched keys: ${items.keys}")
                } else {
                    Log.e("Recommendation", "API error: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e("Recommendation", "Exception: ${e.message}")
            }
        }
    }

    var isRatingSheetVisible by mutableStateOf(false)
        private set

    var selectedStars by mutableStateOf(5)
        private set

    var ratingSubmitting by mutableStateOf(false)
        private set

    fun showRatingSheet() { isRatingSheetVisible = true }
    fun hideRatingSheet() { isRatingSheetVisible = false }
    fun setStars(n: Int) { selectedStars = n.coerceIn(1, 5) }

    fun submitRating(
        outfitId: String,
        items: Map<String, Int>,
        userId: String? = null,
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            ratingSubmitting = true
            try {
                val req = RatingRequest(
                    outfitId = outfitId,
                    rating = selectedStars,
                    items = items,
                    userId = userId
                )

                // network on IO
                val resp = withContext(Dispatchers.IO) {
                    RetrofitInstance.api.postRating(req)
                }

                if (resp.isSuccessful) {
                    Log.d("Rating", "Posted rating ok for outfit=$outfitId")
                    onResult(true, null)
                } else {
                    val errBody = resp.errorBody()?.string()
                    Log.e("Rating", "Server error ${resp.code()} $errBody")
                    onResult(false, "Server error: ${resp.code()}")
                }
            } catch (e: Exception) {
                Log.e("Rating", "Failed to post rating: ${e.message}")
                onResult(false, e.localizedMessage)
            } finally {
                ratingSubmitting = false
                isRatingSheetVisible = false
            }
        }
    }

    suspend fun deleteClothingItem(id: Int): Boolean {
        return try {
            val response = RetrofitInstance.api.deleteClothing(id)
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("DeleteClothing", "Failed to delete id=$id: ${e.message}")
            false
        }
    }

    suspend fun fetchWardrobeItems(occasion: String, preferences: String): List<ClothingItem> {
        return withContext(Dispatchers.IO) {
            try {
                val response = RetrofitInstance.api.getClothes(occasion, preferences)
                if (response.isSuccessful) {
                    val body = response.body() ?: emptyList()
                    Log.d("FetchWardrobe", "Fetched ${body.size} items from API")
                    body
                } else {
                    Log.e("FetchWardrobe", "API call failed with code: ${response.code()}")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e("FetchWardrobe", "Exception during API call: ${e.message}")
                emptyList()
            }
        }
    }
}