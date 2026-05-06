package com.example.specuraprototype

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class HistoryManager(context: Context) {
    private val prefs = context.getSharedPreferences("history_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveHistoryItem(item: HistoryItem) {
        val history = getHistory().toMutableList()
        history.add(0, item)
        prefs.edit().putString("history_list", gson.toJson(history)).apply()
    }

    fun getHistory(): List<HistoryItem> {
        val json = prefs.getString("history_list", null) ?: return emptyList()
        val type = object : TypeToken<List<HistoryItem>>() {}.type
        return gson.fromJson(json, type)
    }
}
