package com.jma.pulsehealthanalytics

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED){
                ApiClient.sessionExpired.collect {
                    Toast.makeText(this@MainActivity, R.string.session_expired, Toast.LENGTH_SHORT).show()
                    showLogin()
                }
            }
        }

    }
    fun showLogin() {
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, LoginFragment())
            .commit()
    }
}