package com.we.meet.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.we.meet.WeMeetApp

/** Navigation owns the controller; Activity recreation and backgrounding do not stop it. */
internal class BilingualTranslationViewModel(val controller: BilingualTranslationController) : ViewModel() {
    override fun onCleared() { controller.close() }

    class Factory(private val app: WeMeetApp, private val user: String?) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BilingualTranslationViewModel::class.java))
            return BilingualTranslationViewModel(BilingualTranslationController(app, app.apiClient.assistantTranslationApi,
                authorized = { user != null && app.captureAccount == user },
                preferences = user?.let { BilingualPreferences(app, it) },
                history = user?.let { com.we.meet.feature.assistant.history.AssistantHistoryStore.get(app, it) { app.captureAccount } })) as T
        }
    }
}
