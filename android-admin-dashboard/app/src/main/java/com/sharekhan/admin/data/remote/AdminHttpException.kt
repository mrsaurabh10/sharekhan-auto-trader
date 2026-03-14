package com.sharekhan.admin.data.remote

import java.io.IOException

class AdminHttpException(
    val code: Int,
    message: String,
    val responseBody: String? = null
) : IOException("HTTP $code: $message")

