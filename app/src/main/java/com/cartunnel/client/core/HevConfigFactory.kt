package com.cartunnel.client.core

object HevConfigFactory {
    fun render() = """
        tunnel:
          mtu: 1500
          ipv4: 10.10.0.2
        socks5:
          address: 127.0.0.1
          port: ${XrayConfigFactory.SOCKS_PORT}
          udp: udp
        misc:
          task-stack-size: 24576
          tcp-buffer-size: 4096
          tcp-read-write-timeout: 300000
          udp-read-write-timeout: 60000
    """.trimIndent() + "\n"
}
