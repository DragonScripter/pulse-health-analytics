package com.jma.pulsehealthanalytics

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(R.layout.activity_main){
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null){
            val start = if (ApiClient.tokenStore.getToken() != null){
                HealthFragment()
            } else {
                LoginFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, start)
                .commit()
        }
    }
}