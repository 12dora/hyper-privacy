package io.github.hyperprivacy.core.system

/**
 * Which packages count as the vendor, for every guard that treats "third-party" callers differently
 * (Settings keys, app-list hiding and module self-hiding in system_server, OAID zeroing in the
 * security center).
 *
 * A package is *vendor-trusted* when it is a system app, or when it is signed with a certificate
 * that a system package of the vendor also uses: the platform certificate (that of the
 * [PLATFORM_PACKAGE], shared by the `com.android.*` platform apps) or the certificate of any system
 * package under a [VENDOR_PREFIXES] namespace. HyperOS installs dozens of its own apps (Health,
 * Notes, Weather, Gallery, Xiaomi Shop, the security manager …) as updatable data apps without the
 * system flag, and they must keep reading vendor keys and identifiers.
 *
 * Certificates count with their APK v3 rotation lineage ([InstalledApp.pastSigners], verified by
 * PackageManager at install): a vendor package that rotated its key still trusts, and is trusted
 * through, the certificate it rotated away from, and so does an app that rotated away from a vendor
 * certificate (which only the vendor could sign off).
 *
 * Only system packages define trust, so a sideloaded app cannot add a certificate by its package
 * name. Certificates of other `com.android.*` system packages (mainline modules, Google apps on
 * global builds) are not trusted: they are shared with ordinary store apps.
 */
public object VendorTrust {
    /** The framework package; its signer is the platform certificate. */
    public const val PLATFORM_PACKAGE: String = "android"

    /** Package namespaces of the vendor's own apps. */
    public val VENDOR_PREFIXES: List<String> = listOf("com.miui.", "com.xiaomi.", "com.mi.", "miui.")

    public fun isVendorNamespace(packageName: String): Boolean = VENDOR_PREFIXES.any(packageName::startsWith)

    /** Signer digests that make a package vendor-trusted, from the installed [apps]. */
    public fun trustedSigners(apps: Iterable<InstalledApp>): Set<String> = apps
        .filter { it.isSystem && (it.packageName == PLATFORM_PACKAGE || isVendorNamespace(it.packageName)) }
        .flatMapTo(HashSet()) { it.signers + it.pastSigners }

    /**
     * Whether [app] is a system app or shares a signer, current or past, with [trusted] (see
     * [trustedSigners]).
     */
    public fun isVendorTrusted(app: InstalledApp, trusted: Set<String>): Boolean =
        app.isSystem || app.signers.any { it in trusted } || app.pastSigners.any { it in trusted }
}
