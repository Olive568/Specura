package com.example.specuraprototype

object LocationHelper {
    /**
     * Standardizes the location tag format:
     * - Trims whitespace
     * - Converts to Uppercase for consistency
     */
    fun normalizeLocationTag(input: String): String {
        return input.trim().uppercase()
    }

    /**
     * Basic validation for location tag
     */
    fun isValid(input: String): Boolean {
        return input.trim().isNotEmpty()
    }
}
