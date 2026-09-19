package com.cartunnel.client.profile

data class VmessWsProfile(
    val schemaVersion: Int = 2,
    val id: String,
    val name: String,
    val server: String,
    val port: Int = 818,
    val uuid: String,
    val wsHost: String,
    val wsPath: String = "/",
)

data class FieldError(val field: String, val message: String)
class ProfileValidationException(val errors: List<FieldError>) :
    IllegalArgumentException(errors.joinToString { "${it.field}: ${it.message}" })
