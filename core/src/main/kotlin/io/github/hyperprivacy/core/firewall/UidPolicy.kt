package io.github.hyperprivacy.core.firewall

/**
 * Decides whether a uid may be deny-listed. Cutting a shared or system uid would take down every
 * package sharing it; for `android.uid.system` that includes system_server itself.
 */
public object UidPolicy {
    /** Mirrors `android.os.Process.FIRST_APPLICATION_UID`. */
    public const val FIRST_APPLICATION_UID: Int = 10_000

    /** Mirrors `android.os.Process.LAST_APPLICATION_UID`. */
    public const val LAST_APPLICATION_UID: Int = 19_999

    /** Mirrors `android.os.UserHandle.PER_USER_RANGE`. */
    public const val PER_USER_RANGE: Int = 100_000

    public enum class Refusal {
        /** Not a regular application uid (system, shared system ids, isolated, SDK sandbox …). */
        NOT_APP_UID,

        /** More than one package runs under this uid. */
        SHARED_UID,
    }

    /** Returns why [uid] must not be firewalled, or `null` if it is safe. */
    public fun refusal(uid: Int, packagesSharingUid: Int): Refusal? {
        val appId = uid % PER_USER_RANGE
        return when {
            uid < 0 || appId !in FIRST_APPLICATION_UID..LAST_APPLICATION_UID -> Refusal.NOT_APP_UID
            packagesSharingUid != 1 -> Refusal.SHARED_UID
            else -> null
        }
    }
}
