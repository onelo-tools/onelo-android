package com.onelo.android

class OneloError(
    val code: Code,
    message: String,
) : Exception(message) {

    enum class Code {
        NOT_AUTHENTICATED,
        HOSTED_FLOW_REQUIRED,
        INVALID_PUBLISHABLE_KEY,
        NETWORK_ERROR,
        SERVER_ERROR,
        CANCELLED,
        REVOKED,
        USER_REVOKED,
        PLAN_REQUIRED,
    }

    companion object {
        fun notAuthenticated() = OneloError(Code.NOT_AUTHENTICATED, "User is not authenticated")
        fun hostedFlowRequired() = OneloError(Code.HOSTED_FLOW_REQUIRED, "This app requires the hosted sign-in flow. Use loadAuthView().")
        fun planRequired() = OneloError(Code.PLAN_REQUIRED, "[plan_required] Custom UI requires a paid Onelo plan. Use loadAuthView() instead.")
        fun invalidKey(msg: String) = OneloError(Code.INVALID_PUBLISHABLE_KEY, "Invalid publishable key: $msg")
        fun network(msg: String) = OneloError(Code.NETWORK_ERROR, "Network error: $msg")
        fun server(msg: String) = OneloError(Code.SERVER_ERROR, msg)
        fun cancelled() = OneloError(Code.CANCELLED, "Sign in was cancelled")
        fun revoked() = OneloError(Code.REVOKED, "This application has been revoked")
        fun userRevoked() = OneloError(Code.USER_REVOKED, "This user account has been suspended")
    }
}
