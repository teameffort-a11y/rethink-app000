    const val PREF_USQUE_ENABLED = "pref_usque_enabled"
    const val PREF_USQUE_REGISTERED = "pref_usque_registered"
    const val PREF_USQUE_TUN_IF = "pref_usque_tun_if"
    const val PREF_USQUE_TUN_IP4 = "pref_usque_tun_ipv4"
    const val PREF_USQUE_TUN_IP6 = "pref_usque_tun_ipv6"

    var usqueEnabled: Boolean
       get() = prefs.getBool(PREF_USQUE_ENABLED, false)
       set(value) = prefs.setBool(PREF_USQUE_ENABLED, value)
       
    var usqueRegistered: Boolean
        get() = sharedPreferences.getBoolean(PREF_USQUE_REGISTERED, false)
        set(value) = sharedPreferences.edit().putBoolean(PREF_USQUE_REGISTERED, value).apply()

    var usqueTunInterface: String?
        get() = sharedPreferences.getString(PREF_USQUE_TUN_IF, null)
        set(value) = sharedPreferences.edit().putString(PREF_USQUE_TUN_IF, value).apply()

    var usqueTunIpv4: String?
        get() = sharedPreferences.getString(PREF_USQUE_TUN_IP4, null)
        set(value) = sharedPreferences.edit().putString(PREF_USQUE_TUN_IP4, value).apply()

    var usqueTunIpv6: String?
        get() = sharedPreferences.getString(PREF_USQUE_TUN_IP6, null)
        set(value) = sharedPreferences.edit().putString(PREF_USQUE_TUN_IP6, value).apply()
