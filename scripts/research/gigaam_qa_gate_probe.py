"""New qa2/qa3 gate validation on the pinned prior corpus, not model selection.
Raw corpus/lyrics/emissions stay local. No network or audio/text uploads.
Preprocessing reference onnx-asr0.11.0, copyright2025 Ilya Stupakov, MIT.
See docs/research/licenses/onnx-asr-0.11.0-MIT.txt.
Corpus GTSinger, Yu Zhang et al., NeurIPS2024, CC BY-NC-SA4.0 research only.
Compile the two CtcLyricsAligner revisions and AlignmentContractProbe into
separate supplied classpaths. No raw corpus or lyric words are printed.
"""
import pathlib,json,hashlib,zipfile,io,subprocess,struct,time,unicodedata,argparse
import numpy as np
import onnxruntime as ort
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--assets',type=pathlib.Path,required=True)
parser.add_argument('--corpus',type=pathlib.Path,required=True)
parser.add_argument('--qa2-classpath',type=pathlib.Path,required=True)
parser.add_argument('--qa3-classpath',type=pathlib.Path,required=True)
parser.add_argument('--out',type=pathlib.Path,required=True)
args=parser.parse_args();root=args.assets;corpus=args.corpus
manifest=json.loads((corpus/'manifest.json').read_text())
assert ort.__version__=='1.20.0'
assert hashlib.sha256((root/'v3_ctc.int8.onnx').read_bytes()).hexdigest()=='ceb61454e2e1a2dec5872cbac1de0fe0a4271d1148f6b26b5bda53ff30a12acd'
assert hashlib.sha256((root/'onnx_asr.whl').read_bytes()).hexdigest()=='142d8b3ce7716684992826a269304f5ce9cf1c0fe704b751358e223f45d2a5cf'
filters=np.load(io.BytesIO(zipfile.ZipFile(root/'onnx_asr.whl').read('onnx_asr/preprocessors/data/fbanks.npz')))
window=filters['gigaam_v3_window'];mel=filters['gigaam_v3']
opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
session=None
def emissions(case):
 global session
 out=corpus/(case['id']+'-emissions.npz')
 if out.exists():return np.load(out)['log_probs']
 for previous in manifest['cases']:
  cached=corpus/(previous['id']+'-emissions.npz')
  if previous['wav_sha256']==case['wav_sha256'] and cached.exists():
   p=np.load(cached)['log_probs'];np.savez_compressed(out,log_probs=p);return p
 for ext in ['wav','json']:assert hashlib.sha256((corpus/(case['id']+'.'+ext)).read_bytes()).hexdigest()==case[ext+'_sha256']
 if session is None:session=ort.InferenceSession(str(root/'v3_ctc.int8.onnx'),opts,providers=['CPUExecutionProvider'])
 data=subprocess.run(['ffmpeg','-nostdin','-v','error','-i',str(corpus/(case['id']+'.wav')),'-ac','1','-ar','16000','-f','f32le','-'],capture_output=True,check=True).stdout
 samples=np.frombuffer(data,dtype='<f4');assert 320<=len(samples)<=16000*60
 frames=np.lib.stride_tricks.sliding_window_view(samples,320)[::160]*window
 features=np.log(np.clip(np.abs(np.fft.rfft(frames,320)).astype(np.float32)**2@mel,1e-9,1e9)).T[None].copy()
 p=session.run(['log_probs'],{'features':features,'feature_lengths':np.array([features.shape[-1]],np.int64)})[0][0]
 assert np.isfinite(p).all() and p.shape[1]==34 and np.max(abs(np.exp(p).sum(-1)-1))<1e-5
 np.savez_compressed(out,log_probs=p);return p
def norm(s):return ''.join(c for c in unicodedata.normalize('NFC',s).lower().replace('ё','е')if 'а'<=c<='я')
def distance(a,b):
 row=list(range(len(b)+1))
 for i,x in enumerate(a):
  nxt=[i+1]
  for j,y in enumerate(b):nxt.append(min(nxt[-1]+1,row[j+1]+1,row[j]+(x!=y)))
  row=nxt
 return row[-1]
def gate(p,lines,revision):
 file=corpus/'gate-input.bin'
 with file.open('wb')as f:
  f.write(struct.pack('>i',len(p)));f.write(p.astype('>f4').tobytes());f.write(struct.pack('>i',len(lines)))
  for line in lines:
   b=line.encode('utf-8');f.write(struct.pack('>H',len(b)));f.write(b)
 cp=args.qa2_classpath if revision=='qa2'else args.qa3_classpath
 output=subprocess.run(['java','-cp',str(cp),'AlignmentContractProbe',str(file)],capture_output=True,text=True,check=True).stdout.splitlines()[0]
 return None if output=='DECLINED'else json.loads(output.removeprefix('ACCEPTED '))
cases=[]
for c in manifest['cases']:
 words=[w for w in json.loads((corpus/(c['id']+'.json')).read_text())if w['word']not in ('<SP>','<AP>')and w['word'].strip()]
 cases.append((c,words))
report={'status':'RESEARCH_ONLY_NOT_RELEASE_READY','corpus_revision':manifest['revision'],'purpose':'Validate changed confidence gate on independently annotated prior corpus; no repeated candidate comparison','unit':'one phrase per short clip and stricter diagnostic word-as-line variant','runtime':ort.__version__,'device':'Linux CPU, not Android','cases':[]}
for case,words in cases:
 p=emissions(case);phrase=' '.join(w['word']for w in words);original=norm(phrase)
 wrong=next(' '.join(w['word']for w in ws)for c,ws in cases if c['id']!=case['id']and distance(original,norm(' '.join(w['word']for w in ws)))/max(1,len(original))>.6)
 item={'id':case['id'],'words':len(words),'reference_phrase_onset_ms':words[0]['start_time']*1000,'variants':{}}
 for name,lines in [('phrase',[phrase]),('word_as_line',[w['word']for w in words]),('foreign_phrase',[wrong])]:
  scores={}
  for revision in ['qa2','qa3']:
   predicted=gate(p,lines,revision);v={'accepted':predicted is not None}
   if predicted is not None:
    ref=[words[0]['start_time']*1000]if name=='phrase'else[w['start_time']*1000 for w in words]
    if name!='foreign_phrase':v.update(predicted_ms=predicted,signed_error_ms=[a-b for a,b in zip(predicted,ref)])
   scores[revision]=v
  item['variants'][name]=scores
 report['cases'].append(item);args.out.write_text(json.dumps(report,indent=2))
 print(case['id'],{name:{r:v['accepted']for r,v in values.items()}for name,values in item['variants'].items()},flush=True)
for revision in ['qa2','qa3']:
 for variant in ['phrase','word_as_line','foreign_phrase']:
  values=[x['variants'][variant][revision]for x in report['cases']];errors=[abs(e)for x in values for e in x.get('signed_error_ms',[])]
  print('SUMMARY',revision,variant,'accepted',sum(x['accepted']for x in values),'/',len(values),'p90_ms',float(np.percentile(errors,90))if errors else None,flush=True)

# Same recordings occur in different technique/control paths. Canonicalize by
# source hash and lexicographic ID, independent of score, and report both counts.
seen=set();canonical=[];duplicates={}
for case in sorted(manifest['cases'],key=lambda x:x['id']):
 if case['wav_sha256'] in seen:
  duplicates[case['id']]=min(c['id']for c in manifest['cases']if c['wav_sha256']==case['wav_sha256']);continue
 seen.add(case['wav_sha256']);canonical.append(case['id'])
report['canonical_unique_ids']=canonical;report['duplicate_ids']=duplicates
report['limitations']='One Russian alto singer, short mostly isolated vocals. No full-mix/genre/Android acceptance. Word-as-line is a stricter diagnostic, not app lyric-line unit.'
report['manifest_sha256']=hashlib.sha256((corpus/'manifest.json').read_bytes()).hexdigest()
report['unique_summary']={}
for revision in ['qa2','qa3']:
 report['unique_summary'][revision]={}
 for variant in ['phrase','word_as_line','foreign_phrase']:
  values=[c['variants'][variant][revision]for c in report['cases']if c['id']in canonical]
  errors=[abs(e)for x in values for e in x.get('signed_error_ms',[])]
  report['unique_summary'][revision][variant]={'accepted':sum(x['accepted']for x in values),'total':len(values),'onsets_measured':len(errors),'median_abs_ms':float(np.median(errors))if errors else None,'p90_abs_ms':float(np.percentile(errors,90))if errors else None,'max_abs_ms':max(errors)if errors else None,'within500_count':sum(e<=500 for e in errors)}
args.out.write_text(json.dumps(report,indent=2))
