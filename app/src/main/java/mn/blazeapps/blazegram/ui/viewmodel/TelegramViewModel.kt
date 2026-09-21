package mn.blazeapps.blazegram.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import mn.blazeapps.blazegram.data.TelegramRepository
import mn.blazeapps.blazegram.data.UserPreferences
import mn.blazeapps.blazegram.data.model.AuthState
import mn.blazeapps.blazegram.data.model.ChatSummary

class TelegramViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TelegramRepository.getInstance(application)
    private val prefs = UserPreferences(application)

    val authState: StateFlow<AuthState> = repository.authState
    val chats: StateFlow<List<ChatSummary>> = repository.chats
    val isLoadingChats: StateFlow<Boolean> = repository.isLoadingChats
    val selectedChatId: StateFlow<Long?> = repository.selectedChatId

    var apiIdInput by mutableStateOf(if (prefs.apiId > 0) prefs.apiId.toString() else "")
    var apiHashInput by mutableStateOf(prefs.apiHash)
    var phoneInput by mutableStateOf(prefs.phoneNumber)
    var otpInput by mutableStateOf("")
    var passwordInput by mutableStateOf("")
    var errorMessage by mutableStateOf<String?>(null)

    fun submitParameters() {
        val apiId = apiIdInput.trim().toIntOrNull()
        val apiHash = apiHashInput.trim()
        if (apiId == null || apiId <= 0 || apiHash.isBlank()) {
            errorMessage = "Please enter valid API ID and API Hash"
            return
        }
        errorMessage = null
        repository.applyTdlibParameters(apiId, apiHash)
    }

    fun submitPhoneNumber() {
        val phone = phoneInput.trim()
        if (phone.isBlank()) {
            errorMessage = "Please enter a valid phone number"
            return
        }
        errorMessage = null
        repository.setPhoneNumber(phone)
    }

    fun submitOtp() {
        val code = otpInput.trim()
        if (code.isBlank()) {
            errorMessage = "Please enter the OTP verification code"
            return
        }
        errorMessage = null
        repository.sendCode(code)
    }

    fun submitPassword() {
        val password = passwordInput.trim()
        if (password.isBlank()) {
            errorMessage = "Please enter your 2FA password"
            return
        }
        errorMessage = null
        repository.sendPassword(password)
    }

    fun selectChat(chatId: Long) {
        repository.selectChat(chatId)
    }

    fun loadAllChats() {
        repository.loadAllChats()
    }

    fun logout() {
        repository.logout()
        apiIdInput = ""
        apiHashInput = ""
        phoneInput = ""
        otpInput = ""
        passwordInput = ""
        errorMessage = null
    }

    fun clearError() {
        errorMessage = null
    }
}
