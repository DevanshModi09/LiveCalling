#!/usr/bin/env python3
"""Create a reproducible robustness suite from explicitly labeled speech. No detector is bundled."""
import argparse, array, hashlib, json, math, random, subprocess, sys, wave
from pathlib import Path
SR = 16000

def read_pcm(path):
    raw = subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-ac','1','-ar',str(SR),'-f','s16le','-'])
    data = array.array('h'); data.frombytes(raw)
    if sys.byteorder != 'little': data.byteswap()
    return list(data)

def write_pcm(path, data):
    pcm = array.array('h', (max(-32768, min(32767, round(x))) for x in data))
    if sys.byteorder != 'little': pcm.byteswap()
    with wave.open(str(path), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())

def loop(data, n):
    if len(data) < SR * 2: raise ValueError('Source speech must be at least two seconds')
    return (data * math.ceil(n / len(data)))[:n]

def ffmpeg(source, output, *args):
    subprocess.run(['ffmpeg','-y','-v','error','-i',str(source),*args,str(output)],check=True)

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sources', required=True, help='JSON list of {path,label}, label genuine or synthetic')
    parser.add_argument('--output', required=True)
    parser.add_argument('--partial-count', type=int, default=100)
    args = parser.parse_args()
    if args.partial_count < 100: parser.error('At least 100 partial-fake samples are required')
    out = Path(args.output); out.mkdir(parents=True,exist_ok=True)
    if (out/'manifest.jsonl').exists(): parser.error('Use a fresh output folder to preserve existing results')
    rows = json.loads(Path(args.sources).read_text()); groups = {'genuine':[], 'synthetic':[]}
    provenance = []
    for row in rows:
        p = Path(row['path']); label = row['label']
        if label not in groups: raise ValueError('Invalid source label')
        groups[label].append((p.name, read_pcm(p)))
        provenance.append({'name':p.name,'label':label,'sha256':digest(p)})
    if not all(groups.values()): raise ValueError('Need genuine and synthetic sources')
    rng = random.Random(20261024); manifest=[]
    base_count=args.partial_count+30
    for i in range(base_count):
        category = 'partial' if i < args.partial_count else ('synthetic' if i < args.partial_count+10 else ('genuine' if i < args.partial_count+20 else 'genuine_splice_control'))
        name, real = groups['genuine'][i % len(groups['genuine'])]
        fake_name, fake = groups['synthetic'][(i // len(groups['genuine'])) % len(groups['synthetic'])]
        data = loop(real, 8*SR); fake = loop(fake, 8*SR); segments=[]
        sources=[name]
        if category == 'partial':
            # Half-second aligned, two disjoint intervals; no amplitude-based label assignment.
            start = rng.choice([1,2,3]); end = start + rng.choice([1,2])
            segments=[(start*.5,end*.5),(rng.choice([8,9,10])*.5,6.5)]
            for a,b in segments: data[round(a*SR):round(b*SR)] = fake[round(a*SR):round(b*SR)]
            sources.append(fake_name)
        elif category == 'synthetic': data=fake; segments=[(0.0,8.0)]; sources=[fake_name]
        elif category == 'genuine_splice_control':
            other_name, other = groups['genuine'][(i+1)%len(groups['genuine'])]; other=loop(other,8*SR)
            data[2*SR:4*SR]=other[2*SR:4*SR]; sources.append(other_name)
        stem=f'{category}_{i:03d}'; clean=out/f'{stem}_clean.wav'; write_pcm(clean,data)
        variants=[('clean',clean,segments,8.0)]
        phone=out/f'{stem}_8khz.wav'
        ffmpeg(clean,phone,'-ar','8000','-c:a','pcm_s16le')
        variants.append(('8khz',phone,segments,8.0))
        for codec,opts in [('mulaw',['-ar','8000','-c:a','pcm_mulaw']),('opus',['-ar','48000','-c:a','libopus','-b:a','16k'])]:
            encoded=out/f'{stem}.{ "wav" if codec=="mulaw" else "ogg"}'
            ffmpeg(clean,encoded,*opts)
            decoded=out/f'{stem}_{codec}.wav'; ffmpeg(encoded,decoded,'-ac','1','-ar',str(SR),'-c:a','pcm_s16le'); encoded.unlink()
            variants.append((codec,decoded,segments,8.0))
        power=sum(x*x for x in data)/len(data); noise=[rng.gauss(0,1) for _ in data]
        noise_power=sum(x*x for x in noise)/len(noise); scale=math.sqrt(power/(10*noise_power)) if power else 0
        noisy=out/f'{stem}_noise10db.wav'; write_pcm(noisy,[.5*(x+n*scale) for x,n in zip(data,noise)])
        variants.append(('noise10db',noisy,segments,8.0))
        quiet=out/f'{stem}_gain025.wav'; write_pcm(quiet,[x*.25 for x in data]); variants.append(('gain025',quiet,segments,8.0))
        padded=out/f'{stem}_silence1s.wav'; write_pcm(padded,[0]*SR+data); variants.append(('silence1s',padded,[(a+1,b+1) for a,b in segments],9.0))
        extended=out/f'{stem}_length10s.wav'; write_pcm(extended,data+loop(real,2*SR)); variants.append(('length10s',extended,segments,10.0))
        for condition,path,truth,duration in variants:
            labels=[int(any(max(j*.5,a)<min((j+1)*.5,b) for a,b in truth)) for j in range(round(duration*2))]
            manifest.append({'id':f'{stem}_{condition}','base_id':stem,'category':category,'condition':condition,'path':path.name,'duration_s':duration,'segments':truth,'labels_0_5s':labels,'sources':sources,'sha256':digest(path)})
        if (i+1)%10==0: print(f'{i+1}/{base_count} base samples',flush=True)
    (out/'manifest.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in manifest))
    (out/'provenance.json').write_text(json.dumps({'sources':provenance,'seed':20261024,'partial_count':args.partial_count,'note':'Diagnostic augmentations reuse sources; not an independent held-out accuracy benchmark. Splices and looping may introduce artifacts; genuine splice controls are included.'},indent=2))
    print(f'Created {len(manifest)} cases from {base_count} base samples')
if __name__ == '__main__': main()
