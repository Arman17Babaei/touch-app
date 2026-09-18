package app.touch.communication

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.touchSettingsDataStore by preferencesDataStore(name = "touch-communication-settings")

internal class SettingsStore(private val context: Context) {
    private object Keys {
        val installationId = stringPreferencesKey("installation_id")
        val backendUrl = stringPreferencesKey("backend_url")
        val username = stringPreferencesKey("username")
        val peerUsername = stringPreferencesKey("peer_username")
        val fcmToken = stringPreferencesKey("fcm_token")
    }

    val settings: Flow<CommunicationSettings> = context.touchSettingsDataStore.data.map { values ->
        CommunicationSettings(
            installationId = values[Keys.installationId].orEmpty(),
            backendUrl = values[Keys.backendUrl].orEmpty(),
            username = values[Keys.username].orEmpty(),
            peerUsername = values[Keys.peerUsername].orEmpty(),
            fcmToken = values[Keys.fcmToken].orEmpty(),
        )
    }

    suspend fun current(): CommunicationSettings = settings.first()

    suspend fun ensureInstallationId(): String {
        current().installationId.takeIf { it.isNotBlank() }?.let { return it }
        val generated = chooseInstallationId(current().installationId) { UUID.randomUUID().toString() }
        context.touchSettingsDataStore.edit { values ->
            if (values[Keys.installationId].isNullOrBlank()) values[Keys.installationId] = generated
        }
        return current().installationId
    }

    suspend fun saveConnection(backendUrl: String, username: String, peerUsername: String) {
        context.touchSettingsDataStore.edit { values ->
            values[Keys.backendUrl] = backendUrl.trim().trimEnd('/')
            values[Keys.username] = username.trim()
            values[Keys.peerUsername] = peerUsername.trim()
        }
    }

    suspend fun saveFcmToken(token: String) {
        context.touchSettingsDataStore.edit { it[Keys.fcmToken] = token }
    }
}

internal fun chooseInstallationId(existing: String, generate: () -> String): String =
    existing.takeIf(String::isNotBlank) ?: generate()
