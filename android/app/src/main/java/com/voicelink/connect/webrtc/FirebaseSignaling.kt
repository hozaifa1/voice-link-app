package com.voicelink.connect.webrtc

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import kotlin.random.Random

/**
 * Firebase Firestore-based signaling for WebRTC.
 * Handles room creation, offer/answer exchange, and ICE candidate synchronization.
 * 
 * Firestore structure:
 * rooms/{roomId}
 *   - offer: { type, sdp }
 *   - answer: { type, sdp }
 *   - createdAt: timestamp
 *   - callerCandidates/{id}: { sdpMid, sdpMLineIndex, candidate }
 *   - calleeCandidates/{id}: { sdpMid, sdpMLineIndex, candidate }
 */
class FirebaseSignaling {
    companion object {
        private const val TAG = "FirebaseSignaling"
        private const val COLLECTION_ROOMS = "rooms"
        private const val COLLECTION_CALLER_CANDIDATES = "callerCandidates"
        private const val COLLECTION_CALLEE_CANDIDATES = "calleeCandidates"
        private const val ROOM_CODE_LENGTH = 6
    }

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    
    private var candidateListener: ListenerRegistration? = null
    private var answerListener: ListenerRegistration? = null
    private var offerListener: ListenerRegistration? = null

    suspend fun signInAnonymously(): Boolean {
        return try {
            if (auth.currentUser == null) {
                auth.signInAnonymously().await()
                Log.d(TAG, "Signed in anonymously: ${auth.currentUser?.uid}")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Anonymous sign-in failed", e)
            false
        }
    }

    suspend fun createRoom(): String {
        signInAnonymously()
        
        val roomId = generateRoomCode()
        Log.d(TAG, "Creating room: $roomId")
        
        val roomData = hashMapOf(
            "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "createdBy" to (auth.currentUser?.uid ?: "unknown")
        )
        
        firestore.collection(COLLECTION_ROOMS)
            .document(roomId)
            .set(roomData)
            .await()
        
        Log.d(TAG, "Room created successfully: $roomId")
        return roomId
    }

    suspend fun roomExists(roomId: String): Boolean {
        signInAnonymously()
        
        val doc = firestore.collection(COLLECTION_ROOMS)
            .document(roomId.uppercase())
            .get()
            .await()
        
        return doc.exists()
    }

    suspend fun sendOffer(roomId: String, offer: SessionDescription) {
        Log.d(TAG, "Sending offer to room: $roomId")
        
        val offerData = hashMapOf(
            "type" to offer.type.canonicalForm(),
            "sdp" to offer.description
        )
        
        try {
            firestore.collection(COLLECTION_ROOMS)
                .document(roomId.uppercase())
                .update("offer", offerData)
                .await()
            Log.d(TAG, "Offer sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send offer", e)
            throw e
        }
    }

    fun getOffer(roomId: String, callback: (SessionDescription?) -> Unit) {
        Log.d(TAG, "Getting offer from room: $roomId")
        
        offerListener?.remove()
        offerListener = firestore.collection(COLLECTION_ROOMS)
            .document(roomId.uppercase())
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error getting offer", error)
                    callback(null)
                    return@addSnapshotListener
                }
                
                val offerData = snapshot?.get("offer") as? Map<*, *>
                if (offerData != null) {
                    val type = offerData["type"] as? String
                    val sdp = offerData["sdp"] as? String
                    
                    if (type != null && sdp != null) {
                        val offer = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(type),
                            sdp
                        )
                        Log.d(TAG, "Offer retrieved successfully")
                        offerListener?.remove()
                        offerListener = null
                        callback(offer)
                    }
                }
            }
    }

    suspend fun sendAnswer(roomId: String, answer: SessionDescription) {
        Log.d(TAG, "Sending answer to room: $roomId")
        
        val answerData = hashMapOf(
            "type" to answer.type.canonicalForm(),
            "sdp" to answer.description
        )
        
        firestore.collection(COLLECTION_ROOMS)
            .document(roomId.uppercase())
            .update("answer", answerData)
            .await()
        
        Log.d(TAG, "Answer sent successfully")
    }

    fun listenForAnswer(roomId: String, callback: (SessionDescription) -> Unit) {
        Log.d(TAG, "Listening for answer in room: $roomId")
        
        answerListener?.remove()
        answerListener = firestore.collection(COLLECTION_ROOMS)
            .document(roomId.uppercase())
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening for answer", error)
                    return@addSnapshotListener
                }
                
                val answerData = snapshot?.get("answer") as? Map<*, *>
                if (answerData != null) {
                    val type = answerData["type"] as? String
                    val sdp = answerData["sdp"] as? String
                    
                    if (type != null && sdp != null) {
                        val answer = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(type),
                            sdp
                        )
                        Log.d(TAG, "Answer received")
                        answerListener?.remove()
                        answerListener = null
                        callback(answer)
                    }
                }
            }
    }

    suspend fun sendIceCandidate(roomId: String, candidate: IceCandidate, isInitiator: Boolean) {
        val collection = if (isInitiator) COLLECTION_CALLER_CANDIDATES else COLLECTION_CALLEE_CANDIDATES
        Log.d(TAG, "Sending ICE candidate to $collection")
        
        val candidateData = hashMapOf(
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "candidate" to candidate.sdp
        )
        
        try {
            firestore.collection(COLLECTION_ROOMS)
                .document(roomId.uppercase())
                .collection(collection)
                .add(candidateData)
                .await()
            Log.d(TAG, "ICE candidate sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ICE candidate", e)
        }
    }

    fun listenForIceCandidates(roomId: String, listenForCaller: Boolean, callback: (IceCandidate) -> Unit) {
        val collection = if (listenForCaller) COLLECTION_CALLER_CANDIDATES else COLLECTION_CALLEE_CANDIDATES
        Log.d(TAG, "Listening for ICE candidates in $collection")
        
        candidateListener?.remove()
        candidateListener = firestore.collection(COLLECTION_ROOMS)
            .document(roomId.uppercase())
            .collection(collection)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening for ICE candidates", error)
                    return@addSnapshotListener
                }
                
                snapshots?.documentChanges?.forEach { change ->
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val data = change.document.data
                        val sdpMid = data["sdpMid"] as? String
                        val sdpMLineIndex = (data["sdpMLineIndex"] as? Long)?.toInt()
                        val candidateSdp = data["candidate"] as? String
                        
                        if (sdpMid != null && sdpMLineIndex != null && candidateSdp != null) {
                            val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
                            Log.d(TAG, "ICE candidate received from $collection")
                            callback(candidate)
                        }
                    }
                }
            }
    }

    suspend fun leaveRoom(roomId: String) {
        Log.d(TAG, "Leaving room: $roomId")
        
        candidateListener?.remove()
        candidateListener = null
        
        answerListener?.remove()
        answerListener = null
        
        offerListener?.remove()
        offerListener = null
    }

    fun cleanup() {
        candidateListener?.remove()
        answerListener?.remove()
        offerListener?.remove()
        candidateListener = null
        answerListener = null
        offerListener = null
    }

    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..ROOM_CODE_LENGTH)
            .map { chars[Random.nextInt(chars.length)] }
            .joinToString("")
    }
}
