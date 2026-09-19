package com.cartunnel.client.profile

object ProfileUriCodec {
    fun decode(value: String): VmessWsProfile = VmessUriCodec.decode(value)
    fun encode(profile: VmessWsProfile): String = VmessUriCodec.encode(profile)
}
