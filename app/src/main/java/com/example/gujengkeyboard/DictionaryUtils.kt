package com.example.gujengkeyboard

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

object DictionaryUtils {

    // 🔥 BACKUP WORDS (Agar file load na ho to ye dikhenge)
    private val dictionaryWords = ArrayList<String>().apply {
        addAll(listOf(
            "the", "to", "and", "a", "of", "in", "is", "you", "that", "it", "he", "was", "for", "on", "are",
            "hai", "ho", "hu", "tha", "thi", "ka", "ki", "ke", "ko", "se", "me", "main", "hum", "tum", "aap",
            "kya", "kyun", "kab", "kahan", "kaise", "kon", "kiska",
            "nahi", "ha", "haan", "bhi", "lekin", "magar", "agar", "par",
            "good", "bad", "hello", "hi", "thanks", "please", "sorry", "yes", "no",
            "kal", "aaj", "abhi", "baad", "pehle", "subah", "raat",
            "ghar", "office", "school", "college", "market", "kaam", "baat",
            "love", "like", "best", "super", "badiya", "thik", "sahi", "galat",
            "problem", "issue", "help", "support", "call", "message",
            "bhai", "bro", "yaar", "dost", "sir", "mam"
        ))
    }

    private val userCustomDictionary = HashSet<String>()
    private val bigramMap = HashMap<String, HashMap<String, Int>>()
    var isLoaded = false

    init {
        // Shuruat mein backup words ko memory mein daal do
        userCustomDictionary.addAll(dictionaryWords)
    }

    // File Load Function (Background)
    // 🔥 DEBUG VERSION: LOAD DICTIONARY
    fun loadDictionary(context: Context) {
        if (isLoaded) return

        Thread {
            // 🔥 UPDATE: Aapke folder wale asli naam
            val fileNames = listOf("words_alpha.txt", "hinglish_words.txt", "guj_words.txt")
            android.util.Log.d("DictionaryUtils", "Starting to load files...")

            for (filename in fileNames) {
                try {
                    // Check karein file exist karti hai ya nahi
                    val assets = context.assets.list("")
                    if (assets != null && assets.contains(filename)) {
                        android.util.Log.d("DictionaryUtils", "File FOUND: $filename")

                        val inputStream = context.assets.open(filename)
                        val reader = BufferedReader(InputStreamReader(inputStream))

                        var count = 0
                        var line = reader.readLine()
                        while (line != null) {
                            val word = line.trim()
                            if (word.isNotEmpty() && !userCustomDictionary.contains(word)) {
                                dictionaryWords.add(word)
                                userCustomDictionary.add(word)
                                count++
                            }
                            line = reader.readLine()
                        }
                        reader.close()
                        android.util.Log.d("DictionaryUtils", "Loaded $count words from $filename")
                    } else {
                        android.util.Log.e("DictionaryUtils", "File NOT FOUND in assets: $filename")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DictionaryUtils", "Error loading $filename: ${e.message}")
                    e.printStackTrace()
                }
            }
            isLoaded = true
            android.util.Log.d("DictionaryUtils", "Total words loaded: ${dictionaryWords.size}")
        }.start()
    }

    fun learnWordPair(prevWord: String, currentWord: String) {
        if (prevWord.isEmpty() || currentWord.isEmpty()) return
        val pWord = prevWord.lowercase(Locale.ROOT)
        val cWord = currentWord.lowercase(Locale.ROOT)

        if (!userCustomDictionary.contains(cWord)) {
            userCustomDictionary.add(cWord)
            dictionaryWords.add(cWord)
        }

        if (!bigramMap.containsKey(pWord)) {
            bigramMap[pWord] = HashMap()
        }
        val nextWords = bigramMap[pWord]!!
        nextWords[cWord] = nextWords.getOrDefault(cWord, 0) + 1
    }

    fun getSuggestions(currentInput: String, previousWord: String): List<String> {
        val suggestions = ArrayList<String>()
        val input = currentInput.lowercase(Locale.ROOT).trim()
        val prev = previousWord.lowercase(Locale.ROOT).trim()

        // CASE A: Next Word Prediction
        if (input.isEmpty() && prev.isNotEmpty()) {
            if (bigramMap.containsKey(prev)) {
                val likelyNextWords = bigramMap[prev]!!.entries
                    .sortedByDescending { it.value }
                    .map { it.key }
                    .take(3)
                suggestions.addAll(likelyNextWords)
            }
        }
        // CASE B: Current Word Completion
        else if (input.isNotEmpty()) {
            val matches = dictionaryWords.asSequence()
                .filter { it.startsWith(input, ignoreCase = true) }
                .sortedBy { it.length }
                .take(3)
                .toList()
            suggestions.addAll(matches)
        }

        return suggestions
    }
}