package io.github.hyperprivacy.core.conflict

/**
 * The space between an app label and the Chinese text that follows it: none after a CJK label
 * ("示例模块也在…"), one after a label ending in a Latin letter or digit ("Example 也在…").
 */
public object LabelGap {
    /** [label] followed by a space only when its last character is an ASCII letter or digit. */
    public fun beforeCjk(label: String): String {
        val last = label.trimEnd().lastOrNull() ?: return label
        return if (last in 'a'..'z' || last in 'A'..'Z' || last in '0'..'9') "$label " else label
    }
}
