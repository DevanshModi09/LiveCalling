#!/usr/bin/env python3
"""Validate generated durations, hashes, class coverage, bins and transformations; no inference."""
import hashlib,json,sys,wave
from collections import Counter,defaultdict
from pathlib import Path
root=Path(sys.argv[1]); rows=[json.loads(s) for s in (root/'manifest.jsonl').read_text().splitlines()]
conditions=defaultdict(set); partial=set(); categories=Counter()
for row in rows:
    p=root/row['path']; assert hashlib.sha256(p.read_bytes()).hexdigest()==row['sha256']
    with wave.open(str(p)) as w:
        rate=w.getframerate(); duration=w.getnframes()/rate
        assert w.getnchannels()==1 and w.getsampwidth()==2
        assert rate==(8000 if row['condition']=='8khz' else 16000)
        assert abs(duration-row['duration_s'])<.03,(row['id'],duration)
    assert len(row['labels_0_5s'])==round(row['duration_s']*2)
    assert all(0<=a<b<=row['duration_s'] for a,b in row['segments'])
    conditions[row['base_id']].add(row['condition']); categories[row['category']]+=1
    if row['category']=='partial':
        partial.add(row['base_id']); assert 0<sum(row['labels_0_5s'])<len(row['labels_0_5s'])
assert len(partial)>=100
required={'clean','8khz','mulaw','opus','noise10db','gain025','silence1s','length10s'}
assert all(c==required for c in conditions.values())
print(json.dumps({'verified_cases':len(rows),'partial_base_samples':len(partial),'categories':dict(categories),'conditions':sorted(required),'model_accuracy_tested':False},indent=2))
