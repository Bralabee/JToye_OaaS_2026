package main

import (
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
)

// trustedProxiesEnv is the environment variable that declares the CIDR ranges
// whose X-Forwarded-For the edge believes. It is deliberately the SAME name
// core-java reads (jtoye.security.trusted-proxies <- TRUSTED_PROXY_CIDRS), so
// one operator input drives both runtimes and there is one answer to "what is
// this client".
const trustedProxiesEnv = "TRUSTED_PROXY_CIDRS"

// clientIPResolver attributes a request to a client address, mirroring core's
// ClientIpResolver (37-24, D-19; RWO-006 / UXT-006) rule for rule:
//
//   - X-Forwarded-For is read ONLY when the socket peer is inside a configured
//     trusted CIDR. From anyone else the header is inert: a client can write
//     any header it likes, but it cannot forge the address its packets come
//     from.
//   - Behind a trusted proxy the hops are walked from the RIGHT, every hop
//     that is itself a trusted proxy is skipped, and the first one that is not
//     is the client. The leftmost value is attacker-controlled and never
//     preferred.
//   - A hop that is not an IP literal ends the walk and the answer is the
//     socket peer (nothing to its left is vouched for); so does a chain made
//     only of trusted proxies.
//   - Literals only, never a DNS lookup; an IPv4-mapped IPv6 literal is
//     folded to its dotted quad so one client cannot hold several buckets by
//     varying its spelling.
//   - The default is EMPTY, trust none.
//
// gin's own c.ClientIP() is NOT used: gin.New() trusts every proxy unless told
// otherwise, which is the exact "believe the header from anyone" shape
// UXT-006 was.
type clientIPResolver struct {
	trusted []netip.Prefix
}

// unknownClient is the bucket key of a request with no usable peer address, so
// a key is never empty.
const unknownClient = "unknown"

// newClientIPResolver parses a comma-separated CIDR list. An empty list trusts
// none. A /0, a prefix outside the address size, or an entry that is not an IP
// literal is an error naming the variable and the entry: "trust everyone" is
// UXT-006 again.
func newClientIPResolver(cidrs string) (*clientIPResolver, error) {
	r := &clientIPResolver{}
	for _, raw := range strings.Split(cidrs, ",") {
		entry := strings.TrimSpace(raw)
		if entry == "" {
			continue
		}
		prefix, err := parseTrustedEntry(entry)
		if err != nil {
			return nil, fmt.Errorf("%s: entry %q %w", trustedProxiesEnv, entry, err)
		}
		r.trusted = append(r.trusted, prefix)
	}
	return r, nil
}

func parseTrustedEntry(entry string) (netip.Prefix, error) {
	addrText, bitsText, hasSlash := strings.Cut(entry, "/")
	addr, err := netip.ParseAddr(addrText)
	if err != nil {
		return netip.Prefix{}, fmt.Errorf("is not an IP literal (a hostname is never resolved)")
	}
	addr = addr.WithZone("").Unmap()
	bits := addr.BitLen()
	if !hasSlash {
		return netip.PrefixFrom(addr, bits), nil
	}
	n, err := strconv.Atoi(bitsText)
	if err != nil {
		return netip.Prefix{}, fmt.Errorf("has a prefix length that is not a number")
	}
	if n == 0 {
		return netip.Prefix{}, fmt.Errorf("is the whole address space (/0) and would trust every client, re-opening UXT-006")
	}
	if n < 0 || n > bits {
		return netip.Prefix{}, fmt.Errorf("has a prefix length outside 1..%d", bits)
	}
	return netip.PrefixFrom(addr, n).Masked(), nil
}

func (r *clientIPResolver) isTrusted(a netip.Addr) bool {
	for _, p := range r.trusted {
		if p.Contains(a) {
			return true
		}
	}
	return false
}

// resolve returns the canonical client address for a request given its socket
// peer (http.Request.RemoteAddr) and X-Forwarded-For header. Never empty.
func (r *clientIPResolver) resolve(remoteAddr, forwardedFor string) string {
	peerText := strings.TrimSpace(remoteAddr)
	if host, _, err := net.SplitHostPort(peerText); err == nil {
		peerText = host
	}
	if peerText == "" {
		return unknownClient
	}
	peer, err := netip.ParseAddr(peerText)
	if err != nil {
		// Not an IP literal (a unix socket name): keyed as written, never trusted.
		return peerText
	}
	peer = peer.WithZone("").Unmap()
	socketPeer := peer.String()
	if !r.isTrusted(peer) || strings.TrimSpace(forwardedFor) == "" {
		return socketPeer
	}
	hops := strings.Split(forwardedFor, ",")
	for i := len(hops) - 1; i >= 0; i-- {
		hop := strings.TrimSpace(hops[i])
		if hop == "" {
			continue
		}
		addr, err := netip.ParseAddr(hop)
		if err != nil {
			return socketPeer // malformed: nothing to its left is vouched for
		}
		addr = addr.WithZone("").Unmap()
		if r.isTrusted(addr) {
			continue
		}
		return addr.String()
	}
	return socketPeer // every hop was a trusted proxy
}
