package br.com.vovolinux.m2m.machine;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Detecta endereços de rede privada (RFC 1918, CGNAT 100.64/10, link-local, ULA IPv6) — D33. */
public final class PrivateAddress {

    private PrivateAddress() {
    }

    public static boolean is(String host) {
        try {
            InetAddress a = InetAddress.getByName(host);
            if (a.isSiteLocalAddress() || a.isLinkLocalAddress()) {
                return true;
            }
            byte[] b = a.getAddress();
            if (b.length == 4) {
                return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40; // 100.64.0.0/10
            }
            return (b[0] & 0xFE) == 0xFC; // fc00::/7
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
