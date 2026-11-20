#!/usr/bin/env bash
# Builds a NAT lab with Linux network namespaces (run as root):
#
#   [ns home] alice 10.10.0.2 --- 10.10.0.1 [root ns = router: MASQUERADE + stateful firewall] 203.0.113.1
#                                                                  |
#                                         203.0.113.2 [ns inet] Asterisk :5160, STUN :3478, bob
#
# The "internet" namespace has no route to 10.10.0.0/24, and the router drops unsolicited inbound packets,
# exactly like a home router. CONNTRACK_UDP_TIMEOUT shortens how long idle UDP mappings live.
set -euo pipefail
CONNTRACK_UDP_TIMEOUT="${CONNTRACK_UDP_TIMEOUT:-15}"

ip netns add home
ip netns add inet
ip link add h1 type veth peer name h0
ip link add i1 type veth peer name i0
ip link set h0 netns home
ip link set i0 netns inet

ip addr add 10.10.0.1/24 dev h1 && ip link set h1 up
ip addr add 203.0.113.1/24 dev i1 && ip link set i1 up
ip netns exec home ip addr add 10.10.0.2/24 dev h0
ip netns exec home ip link set h0 up
ip netns exec home ip link set lo up
ip netns exec home ip route add default via 10.10.0.1
ip netns exec inet ip addr add 203.0.113.2/24 dev i0
ip netns exec inet ip link set i0 up
ip netns exec inet ip link set lo up

sysctl -qw net.ipv4.ip_forward=1
sysctl -qw net.netfilter.nf_conntrack_udp_timeout="$CONNTRACK_UDP_TIMEOUT" || true
sysctl -qw net.netfilter.nf_conntrack_udp_timeout_stream="$CONNTRACK_UDP_TIMEOUT" || true

iptables -t nat -A POSTROUTING -s 10.10.0.0/24 -o i1 -j MASQUERADE
iptables -A FORWARD -i h1 -o i1 -j ACCEPT
iptables -A FORWARD -i i1 -o h1 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
iptables -A FORWARD -i i1 -o h1 -j DROP
# Like a home router, drop unsolicited traffic to the WAN address itself. Without this, early inbound RTP that
# arrives before our first outbound packet creates a conntrack entry that steals the port mapping.
iptables -A INPUT -i i1 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
iptables -A INPUT -i i1 -j DROP
echo "NAT lab ready (UDP mapping timeout ${CONNTRACK_UDP_TIMEOUT}s)"
