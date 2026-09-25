#!/usr/bin/env python3
"""Per-state percentiles of a devSsrAlternate run (whole-frame gpu_ms and frametime from pzopt-overlay.out):
harness/ssr/tails.py <run> [skip seconds]. Frames in the first 10 % of each period are dropped (the switch)."""
import re,sys,numpy as np
run=sys.argv[1]; skip=float(sys.argv[2]) if len(sys.argv)>2 else 6
con=open(run+'/console.txt',errors='replace').read()
m=re.search(r"alternating every (\d+) ms from epoch_ms (\d+)",con); P,T0=int(m[1]),int(m[2])
rows=open(run+'/pzopt-overlay.out').read().split('\n'); hdr=rows[0].split(','); ix={k:i for i,k in enumerate(hdr)}
d={0:[],1:[]}; t0=None
for r in rows[1:]:
    c=r.split(',')
    if len(c)<len(hdr): continue
    e=int(float(c[ix['epoch_ms']])); t0=t0 or e
    if e-t0<skip*1000: continue
    dt=e-T0; ph=(dt%P)/P
    if dt<0 or ph<0.1: continue
    on=1 if (dt//P)%2==0 else 0
    d[on].append((float(c[ix['gpu_ms']]),float(c[ix['frametime']]),(dt%P)))
for k in (1,0):
    a=np.array(d[k]); g=a[:,0]; f=a[:,1]
    print('on ' if k else 'off','n=%d gpu p50 %.3f p90 %.3f p99 %.3f max %.2f | ft p50 %.3f p99 %.3f max %.2f'%(len(a),*np.percentile(g,[50,90,99]),g.max(),*np.percentile(f,[50,99]),f.max()))
a=np.array(d[1]); big=a[a[:,0]>np.percentile(a[:,0],99)]
print('on-frames above p99: phase within period (ms):',np.sort(big[:,2]).astype(int)[:30])
