package com.secondwaybrowser.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

class AllowlistSyncManager(
    private val context: Context,
    private val onRemoteApplied: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private var allowlistListener: ListenerRegistration? = null
    private var settingsListener: ListenerRegistration? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var remoteLockDurationMs: Long? = null
    private var remotePendingMs: Long? = null
    private var remoteEffectiveAt: Long? = null
    private var settingsLoaded: Boolean = false

    fun isSignedIn(): Boolean = auth.currentUser != null

    fun hasRemoteLockDuration(): Boolean = remoteLockDurationMs != null && remoteLockDurationMs!! > 0

    fun isSettingsLoaded(): Boolean = settingsLoaded

    fun start() {
        if (!isSignedIn()) return
        listenSettings()
        listenAllowlist()
    }

    fun stop() {
        allowlistListener?.remove()
        settingsListener?.remove()
        allowlistListener = null
        settingsListener = null
    }

    fun signOut() {
        stop()
        auth.signOut()
    }

    fun pushAddHost(host: String) {
        val uid = auth.currentUser?.uid ?: return
        val canonical = AllowlistHelper.canonicalHost(host) ?: return
        db.collection("users").document(uid)
            .collection("allowlist").document(canonical)
            .set(
                mapOf(
                    "host" to canonical,
                    "requestedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .addOnSuccessListener {
                db.collection("users").document(uid)
                    .set(
                        mapOf(
                            "lastAllowlistPush" to canonical,
                            "lastAllowlistPushAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
            }
            .addOnFailureListener { onError("Allowlist sync failed: ${it.message}") }
    }

    fun pushRemoveHost(host: String) {
        val uid = auth.currentUser?.uid ?: return
        val canonical = AllowlistHelper.canonicalHost(host) ?: return
        db.collection("users").document(uid)
            .collection("allowlist").document(canonical)
            .delete()
            .addOnSuccessListener {
                db.collection("users").document(uid)
                    .set(
                        mapOf(
                            "lastAllowlistRemove" to canonical,
                            "lastAllowlistRemoveAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
            }
            .addOnFailureListener { onError("Allowlist remove failed: ${it.message}") }
    }

    fun pushSettings(lockDurationMs: Long, pendingMs: Long?, effectiveAt: Long?) {
        val uid = auth.currentUser?.uid ?: return
        val data = mutableMapOf<String, Any>(
            "lockDurationMs" to lockDurationMs,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (pendingMs != null && effectiveAt != null) {
            data["lockDurationPendingMs"] = pendingMs
            data["lockDurationEffectiveAt"] = effectiveAt
        } else {
            data["lockDurationPendingMs"] = FieldValue.delete()
            data["lockDurationEffectiveAt"] = FieldValue.delete()
        }
        db.collection("users").document(uid)
            .set(data, SetOptions.merge())
            .addOnFailureListener { onError("Settings sync failed: ${it.message}") }
    }

    private fun listenSettings() {
        val uid = auth.currentUser?.uid ?: return
        settingsListener?.remove()
        settingsListener = db.collection("users").document(uid)
            .addSnapshotListener { snapshot, error ->
                settingsLoaded = true
                if (error != null) {
                    onError("Firestore listen failed: ${error.message}")
                    mainHandler.post(onRemoteApplied)
                    return@addSnapshotListener
                }
                val snap = snapshot ?: run {
                    remoteLockDurationMs = null
                    remotePendingMs = null
                    remoteEffectiveAt = null
                    mainHandler.post(onRemoteApplied)
                    return@addSnapshotListener
                }
                val data = snap.data
                if (data == null) {
                    remoteLockDurationMs = null
                    remotePendingMs = null
                    remoteEffectiveAt = null
                    mainHandler.post(onRemoteApplied)
                    return@addSnapshotListener
                }
                val lockMs = (data["lockDurationMs"] as? Number)?.toLong()
                val pendingMs = (data["lockDurationPendingMs"] as? Number)?.toLong()
                val effectiveAt = (data["lockDurationEffectiveAt"] as? Number)?.toLong()
                remoteLockDurationMs = lockMs
                remotePendingMs = pendingMs
                remoteEffectiveAt = effectiveAt
                AllowlistHelper.setLockDurationFromSync(context, lockMs, pendingMs, effectiveAt)
                mainHandler.post(onRemoteApplied)
            }
    }

    private fun listenAllowlist() {
        val uid = auth.currentUser?.uid ?: return
        allowlistListener?.remove()
        allowlistListener = db.collection("users").document(uid)
            .collection("allowlist")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError("Firestore listen failed: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                val lockMs = remoteLockDurationMs ?: AllowlistHelper.getLockDurationMs(context)
                val now = System.currentTimeMillis()
                val allowMap = mutableMapOf<String, Long>()
                val waitMap = mutableMapOf<String, Long>()
                for (doc in snapshot.documents) {
                    val host = doc.getString("host") ?: doc.id
                    val ts = doc.getTimestamp("requestedAt")
                    val canonical = AllowlistHelper.canonicalHost(host) ?: continue
                    if (ts == null) continue
                    val requestedAt = ts.toDate().time
                    val endMs = requestedAt + lockMs
                    if (now >= endMs) {
                        val prev = allowMap[canonical]
                        allowMap[canonical] = if (prev == null) endMs else maxOf(prev, endMs)
                    } else {
                        val prev = waitMap[canonical]
                        waitMap[canonical] = if (prev == null) endMs else minOf(prev, endMs)
                    }
                }
                for (host in allowMap.keys) {
                    waitMap.remove(host)
                }
                val allow = allowMap.entries.map { it.key to it.value }
                val wait = waitMap.entries.map { it.key to it.value }
                AllowlistHelper.setAllowlistAndWaitlist(context, allow, wait)
                mainHandler.post(onRemoteApplied)
            }
    }
}
