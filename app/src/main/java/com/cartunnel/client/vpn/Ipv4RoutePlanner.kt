package com.cartunnel.client.vpn

data class Cidr(val address: String, val prefix: Int)
object Ipv4RoutePlanner {
    private val excluded = listOf(Cidr("0.0.0.0",8), Cidr("10.0.0.0",8), Cidr("100.64.0.0",10), Cidr("127.0.0.0",8), Cidr("169.254.0.0",16), Cidr("172.16.0.0",12), Cidr("192.168.0.0",16), Cidr("198.18.0.0",15), Cidr("224.0.0.0",4), Cidr("240.0.0.0",4))
    fun routes(bypassLan: Boolean): List<Cidr> = if (!bypassLan) listOf(Cidr("0.0.0.0", 0)) else subtract(excluded.map { ip(it.address) to it.prefix })
    private fun subtract(blocked: List<Pair<Long, Int>>): List<Cidr> { var result = listOf(0L to 0); blocked.sortedWith(compareBy<Pair<Long,Int>> { it.second }.thenBy { it.first }).forEach { b -> result = result.flatMap { current -> if (!contains(current, b)) listOf(current) else splitSubtract(current, b) } }; return result.map { Cidr(format(it.first), it.second) } }
    private fun contains(a: Pair<Long,Int>, b: Pair<Long,Int>) = a.second <= b.second && (b.first shr (32-a.second)) == (a.first shr (32-a.second))
    private fun splitSubtract(a: Pair<Long,Int>, b: Pair<Long,Int>): List<Pair<Long,Int>> { if (a == b) return emptyList(); val p = a.second + 1; val step = 1L shl (32-p); val left = a.first to p; val right = (a.first + step) to p; return if (contains(left, b)) splitSubtract(left, b) + listOf(right) else listOf(left) + splitSubtract(right, b) }
    private fun ip(s: String) = s.split('.').fold(0L) { n, x -> (n shl 8) or x.toLong() }
    private fun format(v: Long) = listOf(24,16,8,0).joinToString(".") { ((v shr it) and 255).toString() }
}
