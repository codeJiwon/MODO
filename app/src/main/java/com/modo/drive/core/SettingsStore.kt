package com.modo.drive.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.modo.domain.engine.RandomIdGenerator
import kotlinx.coroutines.flow.first

/** 앱 설정. 개인정보·자격 증명은 저장하지 않는다. */
interface SettingsStore {
    suspend fun serverUrl(): String
    suspend fun setServerUrl(url: String)
    /** 이 차량 단말을 구분하는 무작위 id (개인 식별 정보 아님). */
    suspend fun vehicleId(): String
}

private val Context.dataStore by preferencesDataStore(name = "modo_settings")

class DataStoreSettings(private val context: Context) : SettingsStore {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val vehicleIdKey = stringPreferencesKey("vehicle_id")

    override suspend fun serverUrl(): String =
        context.dataStore.data.first()[serverUrlKey] ?: DEFAULT_SERVER_URL

    override suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[serverUrlKey] = url.trim() }
    }

    override suspend fun vehicleId(): String {
        context.dataStore.data.first()[vehicleIdKey]?.let { return it }
        val id = RandomIdGenerator.next("veh")
        context.dataStore.edit { it[vehicleIdKey] = id }
        return id
    }

    companion object {
        /** Android 에뮬레이터에서 개발 PC의 localhost:8080 (backend) */
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080"
    }
}

class InMemorySettings(private var url: String = DataStoreSettings.DEFAULT_SERVER_URL, private val id: String = "veh-test") : SettingsStore {
    override suspend fun serverUrl() = url
    override suspend fun setServerUrl(url: String) { this.url = url }
    override suspend fun vehicleId() = id
}
