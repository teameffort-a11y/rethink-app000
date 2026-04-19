private const val PREF_USQUE_ENABLED = "pref_usque_enabled"
private const val PREF_USQUE_REGISTERED = "pref_usque_registered"
private const val PREF_USQUE_TUN_IF = "pref_usque_tun_if"
private const val PREF_USQUE_TUN_IP4 = "pref_usque_tun_ipv4"
private const val PREF_USQUE_TUN_IP6 = "pref_usque_tun_ipv6"

var usqueEnabled: Boolean
    get() = prefs.getBoolean(PREF_USQUE_ENABLED, false)
    set(value) { prefs.edit().putBoolean(PREF_USQUE_ENABLED, value).apply() }

var usqueRegistered: Boolean
    get() = prefs.getBoolean(PREF_USQUE_REGISTERED, false)
    set(value) { prefs.edit().putBoolean(PREF_USQUE_REGISTERED, value).apply() }

var usqueTunInterface: String?
    get() = prefs.getString(PREF_USQUE_TUN_IF, null)
    set(value) { prefs.edit().putString(PREF_USQUE_TUN_IF, value).apply() }

var usqueTunIpv4: String?
    get() = prefs.getString(PREF_USQUE_TUN_IP4, null)
    set(value) { prefs.edit().putString(PREF_USQUE_TUN_IP4, value).apply() }

var usqueTunIpv6: String?
    get() = prefs.getString(PREF_USQUE_TUN_IP6, null)
    set(value) { prefs.edit().putString(PREF_USQUE_TUN_IP6, value).apply() }
