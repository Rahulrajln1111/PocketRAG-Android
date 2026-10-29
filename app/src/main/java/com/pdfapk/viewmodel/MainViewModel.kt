package com.pdfapk.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfapk.data.RecentPdfEntry
import com.pdfapk.data.SettingsManager
import com.pdfapk.llm.LlmEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val llmEngine = LlmEngine(application)
    val settings = SettingsManager(application)

    val downloadState = llmEngine.downloadState
    val isModelLoaded = llmEngine.isModelLoaded

    val isModelDownloaded: StateFlow<Boolean> = settings.isModelDownloaded
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isFirstLaunch: StateFlow<Boolean> = settings.isFirstLaunch
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val connectionMode: StateFlow<String> = settings.connectionMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "local")

    val groqApiKey: StateFlow<String> = settings.groqApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val groqModel: StateFlow<String> = settings.groqModel
        .stateIn(viewModelScope, SharingStarted.Eagerly, "llama-3.3-70b-versatile")

    val recentPdfs: StateFlow<List<RecentPdfEntry>> = settings.recentPdfs
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            // Try to auto-load the model if already downloaded (only for local mode)
            if (connectionMode.value == "local" && isModelDownloaded.value && !llmEngine.isLoaded()) {
                llmEngine.loadModel()
            }
        }
    }

    fun downloadModel() {
        viewModelScope.launch {
            val result = llmEngine.downloadModel()
            if (result.isSuccess) {
                settings.setModelDownloaded(true)
                settings.setModelPath(LlmEngine.modelFile.absolutePath)
                // Auto-load after download
                llmEngine.loadModel()
            }
        }
    }

    fun setConnectionMode(mode: String) {
        viewModelScope.launch {
            settings.setConnectionMode(mode)
        }
    }

    fun setGroqApiKey(key: String) {
        viewModelScope.launch {
            settings.setGroqApiKey(key)
        }
    }

    fun setGroqModel(model: String) {
        viewModelScope.launch {
            settings.setGroqModel(model)
        }
    }

    fun addRecentPdf(uri: String, title: String) {
        viewModelScope.launch {
            settings.addRecentPdf(uri, title)
        }
    }

    fun completeFirstLaunch() {
        viewModelScope.launch {
            settings.setFirstLaunchComplete()
        }
    }

    override fun onCleared() {
        super.onCleared()
        llmEngine.unload()
    }
}
