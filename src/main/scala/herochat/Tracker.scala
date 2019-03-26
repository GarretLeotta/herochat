package herochat

import scala.collection.JavaConverters._

import scodec.bits._

import java.net.{InetAddress, Inet4Address, Inet6Address, InetSocketAddress, NetworkInterface}


import GCodecs.{inet4Address, inet6Address}
import scodec.Codec
import scodec.codecs.{uint16, vector}

object Tracker {
  val base64Alphabet = Bases.Alphabets.Base64Url
  /* TODO: figure out which address on NIC to use, temporary?
   * create a temporary address maybe..
   * use one that does not begin with fe80
   * ??? use one that has a prefix length of 128
   * This will be tough to handle all networks, implement a fall back manual method as well
   */
  def find_public_ip(): Option[InetAddress] = {
    NetworkInterface.getNetworkInterfaces().asScala.foreach(interface => {
      if (interface.isUp && !interface.isLoopback) {
        //prefix length 128 only
        //isLinkLocalAddress == fe80 //verify
        interface.getInterfaceAddresses.asScala.foreach(addr => {
          if (addr.getNetworkPrefixLength == 128) {
            if (!addr.getAddress.isLinkLocalAddress) {
              return Some(addr.getAddress)
            }
          }
        })
      }
    })
    None
  }

  /* to encode a 128 bit address to base 64 takes 22 characters, that looks like this:
   * "https://herochat.net/aBcDefGHiJKLmnoPQRSTUv"
   * TODO: look into ways to shorten these addresses more
   * TODO: remove require
   */
  def encode_ip_to_url(ipPort: InetSocketAddress): Option[String] = {
    (ipPort.getAddress, ipPort.getPort) match {
      case (address: Inet4Address, port: Int) =>
        Option((Codec[Inet4Address] ~~ uint16).encode(address, port).require.toBase64(base64Alphabet))
      case (address: Inet6Address, port: Int) =>
        Option((Codec[Inet6Address] ~~ uint16).encode(address, port).require.toBase64(base64Alphabet))
      case _ =>
        None
    }
  }

  def decode_url_to_ip(url: String): Option[InetSocketAddress] = {
    BitVector.fromBase64(url, base64Alphabet) match {
      case Some(encodedIP) =>
        if (encodedIP.size == 48) {
          val (address, port) = (inet4Address ~~ uint16).decode(encodedIP).require.value
          Option(new InetSocketAddress(address, port))
        } else if (encodedIP.size == 144) {
          val (address, port) = (inet6Address ~~ uint16).decode(encodedIP).require.value
          Option(new InetSocketAddress(address, port))
        } else {
          None
        }
      case None => None
    }
  }
}


/**
 *
 */
class Tracker(url: String) {

  def get_ip_for_lobby() {

  }


}
