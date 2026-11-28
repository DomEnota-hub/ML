"""Research-only independent WORD onset benchmark. No acceptance calibration or app code.
Preprocessing reference: onnx-asr0.11.0, Copyright2025 Ilya Stupakov, MIT.
Full notice: docs/research/licenses/onnx-asr-0.11.0-MIT.txt.
"""
import argparse, pathlib, json, time, resource, subprocess, zipfile, io, unicodedata
import numpy as np
import onnxruntime as ort

from ctc_reference import align
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--assets',type=pathlib.Path,required=True,help='Verified v3_ctc.int8.onnx, v3_vocab.txt and onnx_asr.whl')
parser.add_argument('--corpus',type=pathlib.Path,required=True,help='Private local manifest.json and id.json/id.wav pairs')
parser.add_argument('--out',type=pathlib.Path,required=True)
args=parser.parse_args()
root=args.assets;data=args.corpus
vocab={p.rsplit(' ',1)[0]:int(p.rsplit(' ',1)[1]) for p in (root/'v3_vocab.txt').read_text().splitlines()}
f=np.load(io.BytesIO(zipfile.ZipFile(root/'onnx_asr.whl').read('onnx_asr/preprocessors/data/fbanks.npz')))
fb=f['gigaam_v3'];window=f['gigaam_v3_window']
opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
session=ort.InferenceSession(str(root/'v3_ctc.int8.onnx'),opts,providers=['CPUExecutionProvider'])
def norm(word):
 s=unicodedata.normalize('NFC',word).lower().replace('ё','е')
 if any(c not in vocab and not c.isspace() and not unicodedata.category(c).startswith('P') for c in s):
  raise ValueError('unsupported annotation character')
 return ''.join(c for c in s if c in vocab and c!='▁')
def encode(words):
 tokens=[];starts=[];ends=[]
 for word in words:
  w=norm(word)
  if not w:raise ValueError('unsupported/empty annotation word')
  if tokens:tokens.append(0)
  starts.append(len(tokens));tokens.extend(vocab[c] for c in w);ends.append(len(tokens))
 return tokens,starts,ends
def dist(a,b):
 prev=list(range(len(b)+1))
 for i,x in enumerate(a):
  row=[i+1]
  for j,y in enumerate(b):row.append(min(row[-1]+1,prev[j+1]+1,prev[j]+(x!=y)))
  prev=row
 return prev[-1]
def infer(audio):
 b=subprocess.run(['ffmpeg','-v','error','-nostdin','-i',str(audio),'-ac','1','-ar','16000','-f','f32le','-'],capture_output=True,check=True).stdout
 s=np.frombuffer(b,dtype='<f4')
 assert 320<=len(s)<=16000*60
 frames=np.lib.stride_tricks.sliding_window_view(s,320)[::160]*window
 spec=np.abs(np.fft.rfft(frames,320)).astype(np.float32)**2
 features=np.log(np.clip(spec@fb,1e-9,1e9)).T[None].copy()
 p=session.run(['log_probs'],{'features':features,'feature_lengths':np.array([features.shape[-1]],dtype=np.int64)})[0][0]
 assert np.isfinite(p).all() and np.max(np.abs(np.exp(p).sum(-1)-1))<1e-5
 return p,len(s)/16000
manifest=json.loads((data/'manifest.json').read_text());cases=[]
for c in manifest['cases']:
 ann=json.loads((data/(c['id']+'.json')).read_text())
 words=[x for x in ann if x['word'] not in ('<SP>','<AP>') and x['word'].strip()]
 cases.append((c,words))
report={'status':'RESEARCH_ONLY_NOT_ACCEPTANCE','dataset_revision':manifest['revision'],'runtime':ort.__version__,'device':'Linux x86_64 CPU','unit':'word_onset','frame_mapping':'40ms starts, not calibrated offset','cases':[]}
for i,(case,words) in enumerate(cases):
 r={k:case[k] for k in ['id','technique','path']};t=time.monotonic()
 try:
  lp,seconds=infer(data/(case['id']+'.wav'));times=list(range(0,len(lp)*40,40))
  original=[w['word'] for w in words];tokens,starts,ends=encode(original)
  pred=align(lp,tokens,times,blank=33,min_peak=1e-12,max_cells=48000000)
  greedy=[];prev=None
  for token in lp.argmax(-1):
   if token!=prev and token!=33:greedy.append(int(token))
   prev=token
  target=''.join(norm(x) for x in original);decoded=''.join(next(k for k,v in vocab.items() if v==n) for n in greedy).replace('▁','')
  r.update(seconds=seconds,frames=len(lp),words=len(words),greedy_cer=dist(target,decoded)/len(target),diagnostic_path=pred is not None)
  if pred:
   onset=[pred[j].onset_ms for j in starts];ref=[w['start_time']*1000 for w in words];err=np.array(onset)-ref;peaks=[min(x.peak_probability for x in pred[a:b]) for a,b in zip(starts,ends)]
   r.update(diagnostic_signed_error_ms=err.tolist(),diagnostic_word_min_peaks=peaks,diagnostic_median_abs_ms=float(np.median(abs(err))),diagnostic_p90_abs_ms=float(np.percentile(abs(err),90)),diagnostic_within500=float(np.mean(abs(err)<=500)),strict_whole_clip_peak05=all(x.peak_probability>=.5 for x in pred))
  wrong=next((w for j,(_,w) in enumerate(cases) if j!=i and dist(target,''.join(norm(x['word']) for x in w))/max(1,len(target))>.5),None)
  if wrong:
   wt,_,_=encode([w['word'] for w in wrong]);wp=align(lp,wt,times,blank=33,min_peak=.5,max_cells=48000000)
   r['wrong_text_peak05_false_accept']=wp is not None
 except Exception as e:r.update(error=type(e).__name__,detail=str(e)[:100])
 r['elapsed_seconds']=time.monotonic()-t;r['peak_rss_kib']=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss;report['cases'].append(r)
 args.out.write_text(json.dumps(report,ensure_ascii=False,indent=2))
 print(r['id'],r['technique'],'words',r.get('words'),'CER',r.get('greedy_cer'),'median',r.get('diagnostic_median_abs_ms'),'p90',r.get('diagnostic_p90_abs_ms'),'gate',r.get('strict_whole_clip_peak05'),'wrong',r.get('wrong_text_peak05_false_accept'),'error',r.get('error'),flush=True)
