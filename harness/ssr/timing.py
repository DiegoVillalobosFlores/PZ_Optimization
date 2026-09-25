#!/usr/bin/env python3
"""Mean and standard error of the devSsrTiming deltas (reflections on - off) of runs: harness/ssr/timing.py <run>...
The first 600-frame window is skipped (start-up)."""
import re,sys
for R in sys.argv[1:]:
    w=[];c=[]
    for l in open(R+'/console.txt',errors='ignore'):
        m=re.search(r'water pass gpu us/frame on=([\d.]+).*?off=([\d.]+).*composite.*?on=([\d.]+) off=([\d.]+)',l)
        if m: w.append(float(m[1])-float(m[2])); c.append(float(m[3])-float(m[4]))
    w=w[1:];c=c[1:]
    if not w: print(R,'no timing'); continue
    sd=lambda a:(sum((x-sum(a)/len(a))**2 for x in a)/max(1,len(a)-1))**.5/len(a)**.5
    print(R.split('/')[-1],'water delta %.1f +-%.1f'%(sum(w)/len(w),sd(w)),'| composite delta %.1f +-%.1f'%(sum(c)/len(c),sd(c)),'| n',len(w))
