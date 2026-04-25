package com.streamsync.app.webrtc

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service to fetch TURN credentials from Cloudflare Realtime TURN API.
 * Cloudflare provides 1TB free bandwidth per month.
 * 
 * Documentation: https://developers.cloudflare.com/realtime/turn/
 */
object CloudflareTurnService {
    private const val TAG = "CloudflareTurnService"
    
    // Cloudflare credentials - these are safe in client code as they only generate TURN credentials
    private const val TURN_TOKEN_ID = "5479272aba52eb4770ca18ccbfb4cd4a"
    private const val API_TOKEN = "320d3fa590ac990877ea0dc7e0b8915e96ced1e07c457602b7fb0cdd55d1e4ba"
    
    // Cloudflare TURN API endpoint
    private const val TURN_API_URL = "https://rtc.live.cloudflare.com/v1/turn/keys/$TURN_TOKEN_ID/credentials/generate"
    
    // Credential TTL in seconds (24 hours)
    private const val CREDENTIAL_TTL = 86400
    
    // Cached credentials
    private var cachedCredentials: TurnCredentials? = null
    private var credentialsExpireAt: Long = 0
    
    data class TurnCredentials(
        val iceServers: List<IceServer>
    )
    
    /**
     * Fetch fresh TURN credentials from Cloudflare.
     * Returns cached credentials if still valid.
     */
    suspend fun getCredentials(): TurnCredentials? {
        // Return cached credentials if still valid (with 5 minute buffer)
        val now = System.currentTimeMillis()
        if (cachedCredentials != null && now < credentialsExpireAt - 300_000) {
            Log.d(TAG, "Using cached TURN credentials")
            return cachedCredentials
        }
        
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching fresh TURN credentials from Cloudflare")
                
                val url = URL(TURN_API_URL)
                val connection = url.openConnection() as HttpURLConnection
                
                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Bearer $API_TOKEN")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                
                // Send TTL in request body
                val requestBody = JSONObject().apply {
                    put("ttl", CREDENTIAL_TTL)
                }
                
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(requestBody.toString())
                    writer.flush()
                }
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_CREATED || responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    Log.d(TAG, "Cloudflare response: $response")
                    
                    val credentials = parseCredentials(response)
                    if (credentials != null) {
                        cachedCredentials = credentials
                        credentialsExpireAt = now + (CREDENTIAL_TTL * 1000L)
                        Log.i(TAG, "Successfully fetched ${credentials.iceServers.size} ICE servers from Cloudflare")
                    }
                    credentials
                } else {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                    Log.e(TAG, "Cloudflare API error: $responseCode - $errorBody")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch TURN credentials", e)
                null
            }
        }
    }
    
    private fun parseCredentials(response: String): TurnCredentials? {
        return try {
            val json = JSONObject(response)
            val iceServersArray = json.getJSONObject("iceServers")
            
            val iceServers = mutableListOf<IceServer>()
            
            // Parse URLs array
            val urlsArray = iceServersArray.getJSONArray("urls")
            val urls = mutableListOf<String>()
            for (i in 0 until urlsArray.length()) {
                urls.add(urlsArray.getString(i))
            }
            
            // Get username and credential
            val username = iceServersArray.getString("username")
            val credential = iceServersArray.getString("credential")
            
            // Create a single IceServer with all URLs
            iceServers.add(
                IceServer(
                    urls = urls,
                    username = username,
                    credential = credential
                )
            )
            
            TurnCredentials(iceServers)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse credentials response", e)
            null
        }
    }
    
    /**
     * Get fallback ICE servers (STUN only) if Cloudflare fails.
     */
    fun getFallbackIceServers(): List<IceServer> {
        return listOf(
            // Google STUN servers (free, always available)
            IceServer(
                urls = listOf("stun:stun.l.google.com:19302"),
                username = null,
                credential = null
            ),
            IceServer(
                urls = listOf("stun:stun1.l.google.com:19302"),
                username = null,
                credential = null
            ),
            // Cloudflare STUN (free, unlimited)
            IceServer(
                urls = listOf("stun:stun.cloudflare.com:3478"),
                username = null,
                credential = null
            ),
            // OpenRelay as backup TURN (limited but works)
            IceServer(
                urls = listOf(
                    "turn:openrelay.metered.ca:80",
                    "turn:openrelay.metered.ca:443",
                    "turn:openrelay.metered.ca:443?transport=tcp"
                ),
                username = "openrelayproject",
                credential = "openrelayproject"
            )
        )
    }
    
    /**
     * Clear cached credentials (useful for testing or forcing refresh).
     */
    fun clearCache() {
        cachedCredentials = null
        credentialsExpireAt = 0
    }
}
