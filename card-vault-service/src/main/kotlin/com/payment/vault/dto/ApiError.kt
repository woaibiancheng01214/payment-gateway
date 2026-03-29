package com.payment.vault.dto

data class ApiError(
    val type: String,
    val code: String,
    val message: String,
    val param: String? = null
) {
    companion object {
        const val TYPE_INVALID_REQUEST = "invalid_request_error"
        const val TYPE_API_ERROR = "api_error"
        const val TYPE_CONFLICT = "conflict_error"

        const val CODE_INVALID_PARAM = "parameter_invalid"
        const val CODE_RESOURCE_NOT_FOUND = "resource_not_found"
        const val CODE_STATE_CONFLICT = "state_conflict"
        const val CODE_SERVICE_UNAVAILABLE = "service_unavailable"
    }
}
