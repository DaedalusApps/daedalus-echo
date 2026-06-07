package com.daedalus.voicenotes.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daedalus.voicenotes.ble.BleManager
import com.daedalus.voicenotes.ble.BleState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DeviceViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = BleManager(application)
    val state: StateFlow<BleState> = bleManager.bleState

    fun scan() = bleManager.startScan()

    fun disconnect() = bleManager.disconnect()

    fun refreshFiles() = viewModelScope.launch {
        bleManager.listFiles()
    }

    fun downloadFile(filename: String, onProgress: (Long) -> Unit) = viewModelScope.launch {
        bleManager.downloadFile(filename, onProgress)
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.destroy()
    }
}
