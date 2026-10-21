package com.jma.pulsehealthanalytics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.jma.pulsehealthanalytics.databinding.FragmentLoginBinding
import kotlinx.coroutines.launch

class LoginFragment : Fragment(){
    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnLogin.setOnClickListener { attemptLogin() }

    }

    private fun attemptLogin(){
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        if (email.isEmpty() || password.isEmpty()) {
            showError("Please enter your email and password.")
            return
        }

        setLoading(true)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = ApiClient.login(email, password)
            setLoading(false)

            when (result.code){
                200 -> {
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, HealthFragment())
                        .commit()
                }
                401 -> showError("Invalid email or password.")
                429 -> showError("Too many attempts. Try again later.")
                -1 -> showError("Can't reach the server. Check your connection.")
                else -> showError("Something went wrong. Please try again.")
                }
            }

        }
    private fun setLoading(loading: Boolean){
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        if (loading) binding.tvError.visibility = View.GONE
    }
    private fun showError(message: String){
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}
