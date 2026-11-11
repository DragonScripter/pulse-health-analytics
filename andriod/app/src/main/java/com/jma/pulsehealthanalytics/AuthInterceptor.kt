package com.jma.pulsehealthanalytics
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor (private val tokenStore: TokenStore, private val Unauthorized: () -> Unit,)
    : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response{
        val original = chain.request()
        val isAuthCall = original.url.encodedPath.startsWith("/api/auth")

        val token = tokenStore.getToken()

        val request = if (token != null && !isAuthCall){

            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else original

        val response = chain.proceed(request)
        if (response.code == 401 && !isAuthCall && token != null){
            tokenStore.clear()
            Unauthorized()
        }
        return response
    }

}