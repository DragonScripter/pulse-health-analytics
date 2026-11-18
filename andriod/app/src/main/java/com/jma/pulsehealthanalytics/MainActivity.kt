package com.jma.pulsehealthanalytics

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(R.layout.activity_main) {

    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bottomNav = findViewById(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        bottomNav.setOnItemReselectedListener { /* already here, do nothing */ }

        val loggedIn = ApiClient.tokenStore.getToken() != null
        bottomNav.visibility = if (loggedIn) View.VISIBLE else View.GONE

        if (savedInstanceState == null) {
            val start: Fragment = if (loggedIn) HealthFragment() else LoginFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, start)
                .commit()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ApiClient.sessionExpired.collect {
                    Toast.makeText(this@MainActivity, R.string.session_expired, Toast.LENGTH_SHORT).show()
                    showLogin()
                }
            }
        }
    }

    private fun showTab(id: Int) {
        val fragment: Fragment = when (id) {
            R.id.nav_sleep -> SleepFragment()
            R.id.nav_workouts -> WorkoutLogger()
            else -> HealthFragment()
        }
        // Switching tabs clears anything pushed on top (e.g. the logger opened from the dashboard button).
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    /** Call after a successful login. */
    fun showMain() {
        bottomNav.visibility = View.VISIBLE
        // Set the highlight without firing the listener, then load the dashboard ourselves.
        bottomNav.menu.findItem(R.id.nav_dashboard).isChecked = true
        showTab(R.id.nav_dashboard)
    }

    fun showLogin() {
        bottomNav.visibility = View.GONE
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, LoginFragment())
            .commit()
    }

    /** Used by the dashboard sleep card. */
    fun openSleepTab() {
        bottomNav.selectedItemId = R.id.nav_sleep
    }
    fun openWorkoutsTab() {
        bottomNav.selectedItemId = R.id.nav_workouts
    }
}