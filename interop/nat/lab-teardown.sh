#!/usr/bin/env bash
# Removes the namespaces, links and firewall rules created by lab-setup.sh.
iptables -t nat -D POSTROUTING -s 10.10.0.0/24 -o i1 -j MASQUERADE 2>/dev/null
iptables -D FORWARD -i h1 -o i1 -j ACCEPT 2>/dev/null
iptables -D FORWARD -i i1 -o h1 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null
iptables -D FORWARD -i i1 -o h1 -j DROP 2>/dev/null
iptables -D INPUT -i i1 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null
iptables -D INPUT -i i1 -j DROP 2>/dev/null
ip link del h1 2>/dev/null
ip link del i1 2>/dev/null
ip netns del home 2>/dev/null
ip netns del inet 2>/dev/null
true
