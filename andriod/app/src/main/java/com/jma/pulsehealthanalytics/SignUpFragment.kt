package com.jma.pulsehealthanalytics

import android.os.Bundle
import android.util.Patterns
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.jma.pulsehealthanalytics.databinding.FragmentSignUpBinding
import kotlinx.coroutines.launch

class SignUpFragment : Fragment(R.layout.fragment_sign_up) {

    private var _binding: FragmentSignUpBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSignUpBinding.bind(view)

        binding.btnSignUp.setOnClickListener { submit() }
        binding.tvGoToLogin.setOnClickListener { goToLogin() }
    }

    private fun submit() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        // Client-side validation (mirrors the server for instant feedback)
        binding.etEmail.error = null
        binding.etPassword.error = null
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.error = getString(R.string.error_invalid_email)
            return
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            binding.etPassword.error = getString(R.string.error_password_short, MIN_PASSWORD_LENGTH)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            val result = ApiClient.signup(email, password)
            if (_binding == null) return@launch   // view was destroyed while waiting
            setLoading(false)

            when (result.code) {
                201 -> {
                    showStatus(getString(R.string.signup_success))
                    goToLogin()
                }
                429 -> showStatus(result.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.error_too_many_attempts))
                400 -> showStatus(result.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.error_signup_failed))
                -1 -> showStatus(getString(R.string.error_network))
                else -> showStatus(getString(R.string.error_signup_failed))
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSignUp.isEnabled = !loading
    }

    private fun showStatus(message: String) {
        binding.tvStatus.text = message
    }

    private fun goToLogin() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, LoginFragment())
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 8   // keep in sync with the server's constants
    }
}