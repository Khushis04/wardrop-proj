package com.example.wardroberec.network

import com.example.wardroberec.api.RetrofitInstance
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UploadHelper {
    suspend fun uploadClothing(
        imageFile: File,
        category: String,
        color: String,
        material: String,
        occasion: String
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val categoryPart = category.toRequestBody("text/plain".toMediaTypeOrNull())
                val colorPart = color.toRequestBody("text/plain".toMediaTypeOrNull())
                val materialPart = material.toRequestBody("text/plain".toMediaTypeOrNull())
                val occasionPart = occasion.toRequestBody("text/plain".toMediaTypeOrNull())

                val requestFile = imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                val imagePart = MultipartBody.Part.createFormData("image", imageFile.name, requestFile)

                val response = RetrofitInstance.api.uploadClothing(
                    categoryPart, colorPart, materialPart, occasionPart, imagePart
                )

                if (response.isSuccessful) {
                    Result.success("Uploaded: ${response.body()}")
                } else {
                    Result.failure(Exception("Upload failed: ${response.errorBody()?.string()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
