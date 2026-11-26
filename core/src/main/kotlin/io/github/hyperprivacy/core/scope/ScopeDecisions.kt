package io.github.hyperprivacy.core.scope

/**
 * Whether tracker protection is on for one app, as far as the module app can tell right now. The
 * LSPosed scope is derived from it, and only a definite [OFF] ever takes an app out of the scope:
 * an unreadable preference is [UNKNOWN], never "off".
 */
public enum class Protection {
    ON,
    OFF,

    /** The preferences could not be read (service not bound, remote call failed): decides nothing. */
    UNKNOWN,
    ;

    public companion object {
        public fun of(on: Boolean): Protection = if (on) ON else OFF

        /**
         * The protection of one app: a write the module app has made but not yet seen confirmed
         * ([pendingWrite]) wins; otherwise [read] returns the stored protect value (`null` when
         * absent). [read] itself is `null` when the preferences are not available, and a read that
         * throws is [UNKNOWN] too.
         */
        public fun resolve(pendingWrite: Boolean?, read: (() -> String?)?): Protection = when {
            pendingWrite != null -> of(pendingWrite)
            read == null -> UNKNOWN
            else -> runCatching(read).fold({ of(it != null) }, { UNKNOWN })
        }
    }
}

/** The decisions of the module app's scope state machine (`ScopeRequests`), kept pure for tests. */
public object ScopeDecisions {
    /** What to do with LSPosed's answer to one scope request. */
    public enum class Answer {
        /** Protection is off now: take the app (back) out of the scope. */
        REMOVE,

        /** Tell the user how the request ended. */
        REPORT,

        /** Neither: a stale answer, or the app's state is unknown. */
        IGNORE,
    }

    /** Whether an app in the scope without an open request leaves it: only when protection is definitely off. */
    public fun shouldRemove(protection: Protection): Boolean = protection == Protection.OFF

    /**
     * Decides from the state at the time the answer arrives, never from the state when the
     * request was made: protection may have been turned off and on again meanwhile.
     *
     * @param approved whether LSPosed added the app to the scope.
     * @param protection the app's protection now.
     * @param current whether this request is still the app's open one (not cancelled or superseded).
     * @param first whether this is the first answer to this request (a timeout counts as one).
     */
    public fun onAnswer(approved: Boolean, protection: Protection, current: Boolean, first: Boolean): Answer = when {
        protection == Protection.OFF -> Answer.REMOVE

        !first -> Answer.IGNORE

        current -> Answer.REPORT

        // An approval of a superseded request also grants what the newer request asks for.
        approved && protection == Protection.ON -> Answer.REPORT

        else -> Answer.IGNORE
    }
}
