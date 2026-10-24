#!/usr/bin/env python3
"""Measure external detector predictions. This does not create or assume a model."""
import argparse,json,math,statistics
from collections import defaultdict
from pathlib import Path

def percentile(values,p):
    if not values:return None
    values=sorted(values);return values[min(len(values)-1,math.ceil(p*len(values))-1)]

def evaluate(manifest,predictions,threshold):
    truth={r['id']:r for r in manifest};pred={}
    for row in predictions:
        if row['id'] in pred or row['id'] not in truth:raise ValueError('Duplicate or unknown prediction id')
        pred[row['id']]=row
    groups=defaultdict(lambda:dict(tp=0,fp=0,fn=0,tn=0,scored=0,total=0,positive_total=0))
    lookup={};latencies=[];inferences=[];cadence=[];errors=[];overloaded=0;ready_updates=0;missing_ready_timing=0
    for key,row in truth.items():
        bins=pred.get(key,{}).get('bins',[]);seen=set();lookup[key]={}
        for b in bins:
            start=b['start_s'];end=b['end_s'];idx=round(start*2)
            if abs(start-idx*.5)>1e-5 or abs(end-start-.5)>1e-5 or idx in seen or not 0<=idx<len(row['labels_0_5s']): raise ValueError('Invalid 0.5-second bin')
            seen.add(idx);score=b.get('score')
            if b.get('evidence')!='READY':
                if score is not None:raise ValueError('Abstentions must not publish a score')
                continue
            if score is None or not math.isfinite(score) or not 0<=score<=1:raise ValueError('Invalid score')
            lookup[key][idx]=score
        for group in [row['condition'],row['category'],'all']:
            c=groups[group];c['total']+=len(row['labels_0_5s']);c['positive_total']+=sum(row['labels_0_5s'])
            for idx,score in lookup[key].items():
                c['scored']+=1;actual=row['labels_0_5s'][idx];positive=score>=threshold
                c['tp' if actual and positive else 'fn' if actual else 'fp' if positive else 'tn']+=1
        updates=pred.get(key,{}).get('updates',[])
        times=[u['audio_time_s'] for u in updates]
        if times!=sorted(set(times)):raise ValueError('Updates must be ordered and unique')
        if times:
            expected=[(i+1)*.5 for i in range(round(row['duration_s']*2))]
            if len(times)!=len(expected) or any(abs(a-b)>1e-5 for a,b in zip(times,expected)):errors.append(key)
        else:errors.append(key)
        for u in updates:
            if u.get('evidence') == 'OVERLOADED': overloaded += 1
            if u.get('evidence') != 'READY': continue
            ready_updates += 1
            if not all(u.get(k) is not None for k in ['completed_wall_ms','audio_captured_wall_ms','inference_ms']): missing_ready_timing += 1
            if 'inference_ms' in u and u['inference_ms'] is not None:
                if not math.isfinite(u['inference_ms']) or u['inference_ms']<0:raise ValueError('Invalid inference latency')
                inferences.append(u['inference_ms'])
            if 'completed_wall_ms' in u and 'audio_captured_wall_ms' in u:
                age=u['completed_wall_ms']-u['audio_captured_wall_ms']
                if not math.isfinite(age) or age<0:raise ValueError('Invalid end-to-end latency')
                latencies.append(age)
        cadence.extend([b-a for a,b in zip(times,times[1:])])
    for c in groups.values():
        tp,fp,fn=c['tp'],c['fp'],c['fn']
        c['precision']=tp/(tp+fp) if tp+fp else None
        c['recall_scored']=tp/(tp+fn) if tp+fn else None
        c['f1_scored']=2*tp/(2*tp+fp+fn) if 2*tp+fp+fn else None
        c['coverage']=c['scored']/c['total'] if c['total'] else 0
        c['recall_including_abstentions']=tp/c['positive_total'] if c['positive_total'] else None
    paired={}
    for condition in ['8khz','mulaw','opus','noise10db','gain025','silence1s','length10s']:
        deltas=[];flips=[]
        for key,row in truth.items():
            if row['condition']!=condition:continue
            clean=lookup.get(row['base_id']+'_clean',{})
            shift=2 if condition=='silence1s' else 0
            for idx,score in clean.items():
                other=lookup[key].get(idx+shift)
                if other is not None:deltas.append(abs(other-score));flips.append((other>=threshold)!=(score>=threshold))
        paired[condition]={'paired_bins':len(deltas),'mean_abs_score_delta':statistics.mean(deltas) if deltas else None,'decision_flip_rate':statistics.mean(flips) if flips else None}
    return {'model_supplied':bool(pred),'missing_cases':len(truth)-len(pred),'groups':dict(groups),'paired_robustness':paired,
        'timing':{'cadence_failures':len(errors),'ready_updates':ready_updates,'overloaded_updates':overloaded,'missing_ready_timing':missing_ready_timing,'e2e_p95_ms':percentile(latencies,.95),'inference_p95_ms':percentile(inferences,.95),'meets_500ms_e2e':(percentile(latencies,.95)<=500 and not missing_ready_timing and not overloaded and not errors and len(pred)==len(truth) and groups['all']['scored']>0) if latencies else None},
        'note':'Accuracy is not established without real model predictions; fixture outputs must not be reported as detector performance.'}

def main():
    p=argparse.ArgumentParser();p.add_argument('--manifest',required=True);p.add_argument('--predictions',required=True);p.add_argument('--threshold',type=float,required=True);p.add_argument('--output',required=True);a=p.parse_args()
    if not 0<=a.threshold<=1:p.error('threshold must be in [0,1]')
    load=lambda f:[json.loads(s) for s in Path(f).read_text().splitlines() if s.strip()]
    report=evaluate(load(a.manifest),load(a.predictions),a.threshold)
    Path(a.output).write_text(json.dumps(report,indent=2));print(json.dumps(report['timing'],indent=2))
if __name__=='__main__':main()
