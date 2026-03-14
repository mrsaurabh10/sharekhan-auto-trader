package com.sharekhan.admin.data.remote

import java.util.concurrent.ConcurrentHashMap
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class SessionCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val key = url.host
        val list = store.getOrPut(key) { mutableListOf() }
        synchronized(list) {
            cookies.forEach { cookie ->
                list.removeAll { it.name == cookie.name && it.matches(url) }
                if (cookie.expiresAt >= System.currentTimeMillis()) {
                    list.add(cookie)
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        synchronized(list) {
            val it = list.iterator()
            while (it.hasNext()) {
                val cookie = it.next()
                if (cookie.expiresAt < now) {
                    it.remove()
                }
            }
            return list.filter { it.matches(url) }
        }
    }

    fun clear() {
        store.clear()
    }

    fun hasSession(): Boolean {
        return store.values.any { cookies ->
            cookies.any { it.name.equals("JSESSIONID", ignoreCase = true) }
        }
    }
}

