package com.secondwaybrowser.app

import android.content.Context
import android.content.SharedPreferences
import java.net.URL

object AllowlistHelper {
    private const val PREFS_NAME = "allowlist_prefs"
    private const val KEY_HOSTS = "allowlist_hosts"
    private const val KEY_TS = "allowlist_ts" // "host1:timestamp1,host2:timestamp2"
    private const val KEY_LOCK_DURATION_MS = "allowlist_lock_duration_ms"
    private const val KEY_LOCK_DURATION_SET = "allowlist_lock_duration_set"
    private const val KEY_LOCK_DURATION_PENDING_MS = "allowlist_lock_duration_pending_ms"
    private const val KEY_LOCK_DURATION_EFFECTIVE_AT = "allowlist_lock_duration_effective_at"
    private const val KEY_WAITLIST = "allowlist_waitlist" // "host:endMs,host2:endMs"
    private const val DEFAULT_LOCK_DURATION_MS = 5 * 60 * 1000L // 5 dakika

    /** Google hesap girişi için her zaman allowlist'te (silinse bile geçerli). */
    private val defaultAllowlistHosts = setOf("accounts.google.com")

    fun isLockDurationSet(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCK_DURATION_SET, false)

    fun setLockDurationSet(context: Context) {
        prefs(context).edit().putBoolean(KEY_LOCK_DURATION_SET, true).apply()
    }

    /** Şu an geçerli lock süresi (pending varsa ve zamanı geldiyse uygular). */
    fun getLockDurationMs(context: Context): Long {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val pending = p.getLong(KEY_LOCK_DURATION_PENDING_MS, -1L)
        val effectiveAt = p.getLong(KEY_LOCK_DURATION_EFFECTIVE_AT, -1L)
        if (pending >= 0 && effectiveAt >= 0 && now >= effectiveAt) {
            p.edit().putLong(KEY_LOCK_DURATION_MS, pending).remove(KEY_LOCK_DURATION_PENDING_MS).remove(KEY_LOCK_DURATION_EFFECTIVE_AT).commit()
            return pending
        }
        return p.getLong(KEY_LOCK_DURATION_MS, DEFAULT_LOCK_DURATION_MS)
    }

    fun setLockDurationMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_LOCK_DURATION_MS, ms.coerceAtLeast(1000L)).apply()
    }

    /** Büyük süre anında uygulanır; küçük süre mevcut süre kadar bekledikten sonra uygulanır. */
    fun setLockDurationWithPolicy(context: Context, newMs: Long) {
        val p = prefs(context)
        val current = getLockDurationMs(context)
        val new = newMs.coerceAtLeast(1000L)
        if (new >= current) {
            p.edit().putLong(KEY_LOCK_DURATION_MS, new).remove(KEY_LOCK_DURATION_PENDING_MS).remove(KEY_LOCK_DURATION_EFFECTIVE_AT).apply()
        } else {
            val effectiveAt = System.currentTimeMillis() + current
            p.edit().putLong(KEY_LOCK_DURATION_PENDING_MS, new).putLong(KEY_LOCK_DURATION_EFFECTIVE_AT, effectiveAt).apply()
        }
    }

    /** Pending indirme varsa (effectiveAt, pendingMs); yoksa null. */
    fun getPendingLockDuration(context: Context): Pair<Long, Long>? {
        val p = prefs(context)
        val pending = p.getLong(KEY_LOCK_DURATION_PENDING_MS, -1L)
        val effectiveAt = p.getLong(KEY_LOCK_DURATION_EFFECTIVE_AT, -1L)
        if (pending >= 0 && effectiveAt >= 0 && effectiveAt > System.currentTimeMillis()) return Pair(pending, effectiveAt)
        return null
    }

    /** Süresi dolan waitlist öğelerini allowlist'e taşır. */
    fun promoteExpiredWaitlist(context: Context) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val entries = parseWaitlist(p.getString(KEY_WAITLIST, "") ?: "")
        val (expired, pending) = entries.partition { it.second <= now }
        if (expired.isEmpty()) return
        val set = (p.getStringSet(KEY_HOSTS, emptySet()) ?: emptySet()).toMutableSet()
        var tsStr = p.getString(KEY_TS, "") ?: ""
        val ts = System.currentTimeMillis()
        for ((host, _) in expired) {
            if (!set.any { it.equals(host, ignoreCase = true) }) {
                set.add(host)
                tsStr = if (tsStr.isEmpty()) "$host:$ts" else "$tsStr,$host:$ts"
            }
        }
        val newWaitlist = pending.joinToString(",") { (h, end) -> "$h:$end" }
        p.edit().putStringSet(KEY_HOSTS, HashSet(set)).putString(KEY_TS, tsStr).putString(KEY_WAITLIST, newWaitlist).commit()
    }

    /** İlk açılışta allowlist boşsa accounts.google.com ekler; uygulama her zaman onunla gelir. */
    fun ensureDefaultAllowlist(context: Context) {
        val p = prefs(context)
        val set = p.getStringSet(KEY_HOSTS, emptySet()) ?: emptySet()
        if (set.isEmpty()) {
            p.edit()
                .putStringSet(KEY_HOSTS, setOf("accounts.google.com"))
                .putString(KEY_TS, "accounts.google.com:${System.currentTimeMillis()}")
                .commit()
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isHostAllowlisted(context: Context, host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = normalizeHost(host) ?: return false
        if (defaultAllowlistHosts.any { it.equals(normalized, ignoreCase = true) || normalized.endsWith(".$it", ignoreCase = true) })
            return true
        promoteExpiredWaitlist(context)
        val set = prefs(context).getStringSet(KEY_HOSTS, emptySet()) ?: emptySet()
        return set.any { entry ->
            entry.equals(normalized, ignoreCase = true) ||
                normalized.endsWith(".$entry", ignoreCase = true)
        }
    }

    /** Site ekleme: önce waitlist'e alınır, lock duration sonra allowlist'e taşınır. */
    fun addHost(context: Context, host: String?): Boolean {
        val normalized = normalizeHost(host) ?: return false
        if (defaultAllowlistHosts.any { it.equals(normalized, ignoreCase = true) }) return false
        val p = prefs(context)
        val set = p.getStringSet(KEY_HOSTS, emptySet()) ?: emptySet()
        if (set.any { it.equals(normalized, ignoreCase = true) }) return false
        val waitlist = parseWaitlist(p.getString(KEY_WAITLIST, "") ?: "")
        if (waitlist.any { (h, end) -> h.equals(normalized, ignoreCase = true) && end > System.currentTimeMillis() }) return false
        val durationMs = getLockDurationMs(context)
        val endMs = System.currentTimeMillis() + durationMs
        val newList = waitlist.filter { (h, _) -> !h.equals(normalized, ignoreCase = true) } + (normalized to endMs)
        val newStr = newList.joinToString(",") { (h, end) -> "$h:$end" }
        p.edit().putString(KEY_WAITLIST, newStr).commit()
        return true
    }

    /** Waitlist: (host, endTimestampMs). Süresi dolanlar promote edilir. */
    fun getWaitlistWithEndTimes(context: Context): List<Pair<String, Long>> {
        promoteExpiredWaitlist(context)
        return parseWaitlist(prefs(context).getString(KEY_WAITLIST, "") ?: "").filter { it.second > System.currentTimeMillis() }
    }

    /** Host bekleme listesinde mi ve kalan süre (ms); yoksa null. */
    fun getWaitlistRemainingMs(context: Context, host: String?): Long? {
        val normalized = normalizeHost(host) ?: return null
        val list = getWaitlistWithEndTimes(context)
        val now = System.currentTimeMillis()
        return list.find { (h, _) -> h.equals(normalized, ignoreCase = true) || normalized.endsWith(".$h", ignoreCase = true) }?.let { (_, end) -> (end - now).coerceAtLeast(0) }
    }

    /** Bekleme listesinden çıkar; anında listeden düşer (allowlist'e alınmaz). */
    fun removeFromWaitlist(context: Context, host: String?): Boolean {
        val normalized = normalizeHost(host) ?: return false
        val p = prefs(context)
        val list = parseWaitlist(p.getString(KEY_WAITLIST, "") ?: "")
        val toRemove = list.find { (h, _) -> h.equals(normalized, ignoreCase = true) || normalized.endsWith(".$h", ignoreCase = true) } ?: return false
        val newList = list.filter { (h, _) -> !h.equals(toRemove.first, ignoreCase = true) }
        val newStr = newList.joinToString(",") { (h, end) -> "$h:$end" }
        p.edit().putString(KEY_WAITLIST, newStr).commit()
        return true
    }

    private fun parseWaitlist(str: String): List<Pair<String, Long>> = str.split(",").mapNotNull { part ->
        val idx = part.lastIndexOf(':')
        if (idx <= 0) return@mapNotNull null
        val h = part.substring(0, idx).trim()
        val end = part.substring(idx + 1).toLongOrNull() ?: return@mapNotNull null
        if (h.isBlank()) null else Pair(h, end)
    }

    fun removeHost(context: Context, host: String?): Boolean {
        val normalized = normalizeHost(host) ?: return false
        val p = prefs(context)
        val set = (p.getStringSet(KEY_HOSTS, emptySet()) ?: emptySet()).toMutableSet()
        // Silinecek entry: tam eşleşme veya normalized bu entry'nin alt alanı (m.youtube.com → youtube.com)
        val toRemove = set.find { entry ->
            entry.equals(normalized, ignoreCase = true) ||
                normalized.endsWith(".$entry", ignoreCase = true)
        } ?: return false
        set.removeAll { it.equals(toRemove, ignoreCase = true) }
        val tsStr = p.getString(KEY_TS, "") ?: ""
        val entries = tsStr.split(",").filter { it.isNotBlank() }
        val newTs = entries.filterNot { part ->
            val idx = part.indexOf(':')
            idx > 0 && part.substring(0, idx).equals(toRemove, ignoreCase = true)
        }.joinToString(",")
        p.edit().putStringSet(KEY_HOSTS, HashSet(set)).putString(KEY_TS, newTs).commit()
        return true
    }

    /** Returns list of (host, timestampMs) sorted by timestamp descending. */
    fun getAllowlistWithTimestamps(context: Context): List<Pair<String, Long>> {
        val p = prefs(context)
        val tsStr = p.getString(KEY_TS, "") ?: ""
        if (tsStr.isBlank()) {
            val set = p.getStringSet(KEY_HOSTS, emptySet()) ?: emptySet()
            return set.map { Pair(it, 0L) }.sortedBy { it.first }
        }
        return tsStr.split(",").mapNotNull { part ->
            val idx = part.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val h = part.substring(0, idx)
            val t = part.substring(idx + 1).toLongOrNull() ?: return@mapNotNull null
            Pair(h, t)
        }.sortedByDescending { it.second }
    }

    /** Replace allowlist + waitlist from remote sync (server is source of truth). */
    fun setAllowlistAndWaitlist(
        context: Context,
        allowlist: List<Pair<String, Long>>,
        waitlist: List<Pair<String, Long>>
    ) {
        val set = allowlist.map { it.first }.toSet()
        val tsStr = allowlist.joinToString(",") { (h, ts) -> "$h:$ts" }
        val waitStr = waitlist.joinToString(",") { (h, end) -> "$h:$end" }
        prefs(context).edit()
            .putStringSet(KEY_HOSTS, HashSet(set))
            .putString(KEY_TS, tsStr)
            .putString(KEY_WAITLIST, waitStr)
            .apply()
    }

    /** Apply lock duration values from remote sync. */
    fun setLockDurationFromSync(
        context: Context,
        lockDurationMs: Long?,
        pendingMs: Long?,
        effectiveAt: Long?
    ) {
        val p = prefs(context).edit()
        if (lockDurationMs != null && lockDurationMs > 0) {
            p.putLong(KEY_LOCK_DURATION_MS, lockDurationMs.coerceAtLeast(1000L))
            p.putBoolean(KEY_LOCK_DURATION_SET, true)
        }
        if (pendingMs != null && effectiveAt != null && pendingMs > 0 && effectiveAt > 0) {
            p.putLong(KEY_LOCK_DURATION_PENDING_MS, pendingMs)
            p.putLong(KEY_LOCK_DURATION_EFFECTIVE_AT, effectiveAt)
        } else {
            p.remove(KEY_LOCK_DURATION_PENDING_MS)
            p.remove(KEY_LOCK_DURATION_EFFECTIVE_AT)
        }
        p.apply()
    }

    private fun normalizeHost(host: String?): String? {
        if (host.isNullOrBlank()) return null
        var h = host.trim().lowercase()
        if (h.startsWith("http://") || h.startsWith("https://")) {
            try {
                h = URL(h).host ?: return null
            } catch (_: Exception) {
                return null
            }
        }
        if (h.startsWith("www.")) h = h.removePrefix("www.")
        return h.ifBlank { null }
    }

    fun canonicalHost(host: String?): String? = normalizeHost(host)

    fun hostFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            URL(url).host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }
}
