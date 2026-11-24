package bnbemu.packet

import bnbemu.netty.PKT
import java.io.ByteArrayOutputStream

// op 66 package map. Each row links one bundle product to a component product.
fun Unk66() = PKT {
    val blob = shopPackageMapBlob
    Encode4(blob.size)
    EncodeBuffer(blob)
}

private val shopPackageMapBlob by lazy {
    val entries = ShopPackages.relationships(ShopCatalog.DEFAULT_ITEMS)
    val plain = ByteArrayOutputStream().apply {
        u8(0x18)
        u32le(3 + entries.size * 8)
        u8(0)
        u16le(entries.size)
        entries.forEach { (bundle, component) ->
            u32le(bundle)
            u32le(component)
        }
    }.toByteArray()
    ssdEncode(plain)
}
