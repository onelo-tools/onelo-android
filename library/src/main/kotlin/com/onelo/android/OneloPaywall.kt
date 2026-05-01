package com.onelo.android

class OneloPaywall {
    private val tier = mapOf("free" to 0, "pro" to 1, "business" to 2, "enterprise" to 3)

    fun check(requiredPlan: String, userPlan: String = "free"): Boolean {
        val req = tier[requiredPlan] ?: return false
        val usr = tier[userPlan] ?: return false
        return usr >= req
    }
}
