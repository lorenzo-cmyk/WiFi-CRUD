package com.wificrud.app.data

import android.content.Context
import android.content.SharedPreferences

class CredentialStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wifi_crud_creds", Context.MODE_PRIVATE)

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var authKey: String
        get() = prefs.getString(KEY_AUTH_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTH_KEY, value).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    val isRegistered: Boolean
        get() = deviceId.isNotEmpty() && authKey.isNotEmpty()

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_AUTH_KEY = "auth_key"
        private const val KEY_DEVICE_NAME = "device_name"
    }
}
