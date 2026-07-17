package app.simmo.store

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * JSON storage format: unknown keys are ignored so an older app version can
 * read state written by a newer one; defaults are encoded so the stored file
 * is self-describing.
 */
private val storageJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

object SimmoStateSerializer : Serializer<SimmoState> {
    override val defaultValue: SimmoState = SimmoState()

    override suspend fun readFrom(input: InputStream): SimmoState =
        try {
            storageJson.decodeFromString<SimmoState>(input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot read Simmo state", e)
        }

    override suspend fun writeTo(t: SimmoState, output: OutputStream) {
        output.write(storageJson.encodeToString(SimmoState.serializer(), t).encodeToByteArray())
    }
}

/**
 * The app-wide store. Corruption falls back to empty state rather than
 * crashing: losing rules is recoverable in the UI; a crash loop in the
 * process that hosts the redirection service is not.
 */
val Context.simmoStateStore: DataStore<SimmoState> by dataStore(
    fileName = "simmo_state.json",
    serializer = SimmoStateSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { SimmoState() },
)
