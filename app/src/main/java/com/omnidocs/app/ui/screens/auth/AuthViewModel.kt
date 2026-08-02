package com.omnidocs.app.ui.screens.auth

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.common.api.ApiException
import com.omnidocs.app.data.remote.DriveService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val driveService: DriveService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _authSuccess = MutableSharedFlow<Unit>()
    val authSuccess: SharedFlow<Unit> = _authSuccess.asSharedFlow()

    private val googleSignInClient: GoogleSignInClient by lazy {
        GoogleSignIn.getClient(context, DriveService.createSignInOptions())
    }

    fun getGoogleSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    fun handleGoogleSignInResult(data: Intent?) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    _authSuccess.emit(Unit)
                    _isLoading.value = false
                } else {
                    _error.value = "Google sign in failed: No account"
                    _isLoading.value = false
                }
            } catch (e: ApiException) {
                _error.value = "Google sign in failed with code: ${e.statusCode}"
                _isLoading.value = false
            } catch (e: Exception) {
                _error.value = "Google sign in error: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    fun signOut() {
        googleSignInClient.signOut()
    }
}
