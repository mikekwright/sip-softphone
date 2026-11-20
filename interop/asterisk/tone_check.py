#!/usr/bin/env python3
"""Prints the share of energy at 440/600/1000 Hz in a WAV recording (Goertzel), to verify which tone was heard."""
import sys,wave,struct,math
w=wave.open(sys.argv[1]); n=w.getnframes(); r=w.getframerate()
d=struct.unpack('<%dh'%n,w.readframes(n)); seg=d[n//3:n//3+4000]
def g(f):
    k=2*math.cos(2*math.pi*f/r);s1=s2=0
    for x in seg: s0=x+k*s1-s2; s2=s1; s1=s0
    return s1*s1+s2*s2-k*s1*s2
p={f:g(f) for f in (440,600,1000)}; tot=sum(p.values()) or 1
print(sys.argv[1].split('/')[-1], "%.1fs"%(n/r), {f:round(v/tot,3) for f,v in p.items()})
