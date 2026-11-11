package com.jma.pulsehealthanalytics
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class AuthResult(val code: Int, val message: String?, val token: String? = null)

object ApiClient {
    lateinit var tokenStore: TokenStore
        private set
    lateinit var http: OkHttpClient
        private set


    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()
    fun init(context: Context) {
        tokenStore = TokenStore(context)
        http = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore) { _sessionExpired.tryEmit(Unit) })
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
        suspend fun postWatchMetrics(fields: Map<String, Int>): Int =
            withContext(Dispatchers.IO) {
                try {
                    val json = JSONObject()
                        .put("captured_at", java.time.Instant.now().toString())
                        .put("date", java.time.LocalDate.now().toString())
                    fields.forEach { (k, v) -> json.put(k, v) }

                    val request = Request.Builder()
                        .url("${BuildConfig.API_URL}/api/watch-metrics")
                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    http.newCall(request).execute().use { it.code }
                } catch (e: Exception) {
                    android.util.Log.e("ApiClient", "watch post failed", e)
                    -1
                }
            }
    fun logout() = tokenStore.clear()




}