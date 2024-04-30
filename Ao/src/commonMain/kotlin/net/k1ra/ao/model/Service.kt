package net.k1ra.ao.model

class Service(
    val device: Device,
    val mtu: Int,
    val uuid: String,
    val characteristics: List<Characteristic>
)