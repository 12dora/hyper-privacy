package io.github.hyperprivacy.xposed.firewall

import android.content.Context
import android.net.ConnectivityManager
import io.github.hyperprivacy.xposed.HookLog
import java.lang.reflect.Method

/**
 * Reflection bridge to the `@SystemApi` uid firewall on [ConnectivityManager], which programs the
 * kernel (BPF) deny list and therefore also covers native and Cronet traffic.
 *
 * Only callable from a process holding NETWORK_SETTINGS, i.e. system_server.
 */
internal class ConnectivityFirewall private constructor(
    private val manager: ConnectivityManager,
    private val chain: Int,
    private val ruleDeny: Int,
    private val ruleDefault: Int,
    private val setChainEnabled: Method,
    private val setUidRule: Method,
) {
    fun enableChain() {
        setChainEnabled.invoke(manager, chain, true)
    }

    fun deny(uid: Int) {
        setUidRule.invoke(manager, chain, uid, ruleDeny)
    }

    fun clear(uid: Int) {
        setUidRule.invoke(manager, chain, uid, ruleDefault)
    }

    companion object {
        // Reserved for OEM use in AOSP; chosen as the least likely to collide with ROM policy.
        private const val CHAIN_NAME = "FIREWALL_CHAIN_OEM_DENY_3"
        private const val EXPECTED_CHAIN_VALUE = 9

        /** Resolves all members up front; returns `null` (logged) if any is missing. */
        fun resolve(context: Context, log: HookLog): ConnectivityFirewall? = log.guard("resolve firewall API") {
            val type = ConnectivityManager::class.java
            val chain = type.getField(CHAIN_NAME).getInt(null)
            if (chain != EXPECTED_CHAIN_VALUE) log.warn("$CHAIN_NAME = $chain (expected $EXPECTED_CHAIN_VALUE)")
            val int = Int::class.javaPrimitiveType
            ConnectivityFirewall(
                manager = context.getSystemService(type),
                chain = chain,
                ruleDeny = type.getField("FIREWALL_RULE_DENY").getInt(null),
                ruleDefault = type.getField("FIREWALL_RULE_DEFAULT").getInt(null),
                setChainEnabled = type.getMethod("setFirewallChainEnabled", int, Boolean::class.javaPrimitiveType),
                setUidRule = type.getMethod("setUidFirewallRule", int, int, int),
            )
        }
    }
}
