package com.jma.pulsehealthanalytics
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class AuthResult(val code: Int, val message: String?, val token: String? = null)

object ApiClient {
    lateinit var tokenStore: TokenStore
        private set
    lateinit var http: OkHttpClient
        private set

    fun init(context: Context) {
        tokenStore = TokenStore(context)
        http = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .build()
    }

    suspend fun signup(email: String, password: String): AuthResult =
        post("/api/auth/signup", email, password)

    suspend fun login(email: String, password: String): AuthResult {
        val result = post("/api/auth/login", email, password)
        if (result.code == 200 && result.token !== null){
            tokenStore.saveToken(result.token)
        }
        return result
    }

    private suspend fun post(path: String, email: String, password: String): AuthResult =
        withContext(Dispatchers.IO){
            try {
                val json = JSONObject().put("email", email).put("password", password)
                val request = Request.Builder()
                    .url("${BuildConfig.API_URL}$path")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(request).execute().use { resp ->
                    val body = JSONObject(resp.body?.string() ?: "{}")
                    AuthResult(
                        code = resp.code,
                        message = body.optString("error", body.optString("message", "")),
                        token = if (body.has("token")) body.getString("token") else null
                    )
                }

            } catch (e : Exception){
                android.util.Log.e("ApiClient", "request failed", e)
                AuthResult(-1, "Network error")
            }
        }




}