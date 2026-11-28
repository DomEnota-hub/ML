"""Changed-frontend validation, chosen GigaAM only; no uploads or raw-lyric output.
Original onnx-asr0.11.0 filters, copyright2025 Ilya Stupakov, MIT;
see docs/research/licenses/onnx-asr-0.11.0-MIT.txt.
GTSinger, Yu Zhang et al., NeurIPS2024, CC BY-NC-SA4.0, research only.
"""
import argparse,pathlib,json,hashlib,zipfile,io,subprocess,struct,unicodedata
import numpy as np
import onnxruntime as ort
p=argparse.ArgumentParser(description=__doc__)
for name in ['assets','corpus','classpath','out']:p.add_argument('--'+name,type=pathlib.Path,required=True)
args=p.parse_args();root=args.assets;corpus=args.corpus
def sha(file):
 with file.open('rb')as f:return hashlib.file_digest(f,'sha256').hexdigest()
assert ort.__version__=='1.20.0'
assert sha(root/'v3_ctc.int8.onnx')=='ceb61454e2e1a2dec5872cbac1de0fe0a4271d1148f6b26b5bda53ff30a12acd'
assert sha(root/'onnx_asr.whl')=='142d8b3ce7716684992826a269304f5ce9cf1c0fe704b751358e223f45d2a5cf'
wheel=zipfile.ZipFile(root/'onnx_asr.whl');filters=np.load(io.BytesIO(wheel.read('onnx_asr/preprocessors/data/fbanks.npz')))
window=filters['gigaam_v3_window'];mel=filters['gigaam_v3']
opt=ort.SessionOptions();opt.intra_op_num_threads=2;opt.inter_op_num_threads=1;opt.enable_cpu_mem_arena=False;opt.enable_mem_pattern=False
session=ort.InferenceSession(str(root/'v3_ctc.int8.onnx'),opt,providers=['CPUExecutionProvider'])
resamplers={}
def emissions(case):
 target=corpus/(case['id']+'-original-emissions.npz')
 if target.exists():return np.load(target)['log_probs']
 for ext in ['json','wav']:assert sha(corpus/(case['id']+'.'+ext))==case[ext+'_sha256']
 audio=corpus/(case['id']+'.wav')
 info=json.loads(subprocess.run(['ffprobe','-v','error','-select_streams','a:0','-show_entries','stream=sample_rate,channels','-of','json',str(audio)],text=True,capture_output=True,check=True).stdout)['streams'][0]
 rate=int(info['sample_rate']);channels=int(info['channels'])
 raw=subprocess.run(['ffmpeg','-nostdin','-v','error','-i',str(audio),'-map','0:a:0','-vn','-ar',str(rate),'-ac',str(channels),'-f','f32le','-'],capture_output=True,check=True).stdout
 samples=np.frombuffer(raw,dtype='<f4').reshape(-1,channels).mean(axis=1,dtype=np.float64).astype(np.float32)
 if rate!=16000:
  if rate not in resamplers:
   o=ort.SessionOptions();o.intra_op_num_threads=1;o.inter_op_num_threads=1;o.enable_cpu_mem_arena=False;o.enable_mem_pattern=False
   resamplers[rate]=ort.InferenceSession(wheel.read('onnx_asr/preprocessors/data/resample_'+str(rate//1000)+'_16.onnx'),o,providers=['CPUExecutionProvider'])
  values,lengths=resamplers[rate].run(None,{'waveforms':samples[None],'waveforms_lens':np.array([len(samples)],np.int64)})
  assert values.shape[1]==lengths[0];samples=values[0]
 assert 320<=len(samples)<=16000*60 and np.isfinite(samples).all()
 pieces=[];positions=[]
 for start in range(0,len(samples),320000):
  end=min(start+320000,len(samples));left=max(0,start-48000);right=min(len(samples),end+48000+320)
  if right-left<320:continue
  framed=np.lib.stride_tricks.sliding_window_view(samples[left:right],320)[::160]*window
  features=np.log(np.clip(np.abs(np.fft.rfft(framed,320)).astype(np.float32)**2@mel,1e-9,1e9)).T[None].copy()
  lp=session.run(['log_probs'],{'features':features,'feature_lengths':np.array([features.shape[-1]],np.int64)})[0][0]
  times=left+np.arange(len(lp))*640;keep=(times>=start)&(times<end)&(times<len(samples)-160)
  pieces.append(lp[keep]);positions.extend(times[keep].tolist())
 lp=np.concatenate(pieces);assert positions==list(range(0,len(lp)*640,640))and np.isfinite(lp).all()
 np.savez_compressed(target,log_probs=lp);return lp
def normalized(text):return ''.join(c for c in unicodedata.normalize('NFC',text).lower().replace('ё','е')if 'а'<=c<='я')
def distance(a,b):
 row=list(range(len(b)+1))
 for i,x in enumerate(a):
  nxt=[i+1]
  for j,y in enumerate(b):nxt.append(min(nxt[-1]+1,row[j+1]+1,row[j]+(x!=y)))
  row=nxt
 return row[-1]
def align(lp,text):
 file=corpus/'qa4-gate-input.bin';data=text.encode('utf8')
 with file.open('wb')as f:f.write(struct.pack('>i',len(lp)));f.write(lp.astype('>f4').tobytes());f.write(struct.pack('>iH',1,len(data)));f.write(data)
 out=subprocess.run(['java','-cp',str(args.classpath),'AlignmentContractProbe',str(file)],capture_output=True,text=True,check=True).stdout.splitlines()[0]
 return None if out=='DECLINED'else json.loads(out.removeprefix('ACCEPTED '))[0]
manifest=json.loads((corpus/'manifest.json').read_text());seen=set();cases=[];excluded=[]
for case in sorted(manifest['cases'],key=lambda x:x['id']):
 if case['wav_sha256']in seen:excluded.append({'id':case['id'],'reason':'duplicate_audio_hash'});continue
 seen.add(case['wav_sha256'])
 if '/Paired_Speech_Group/'in case['path']:excluded.append({'id':case['id'],'reason':'paired_speech'});continue
 assert sha(corpus/(case['id']+'.json'))==case['json_sha256']
 words=[w for w in json.loads((corpus/(case['id']+'.json')).read_text())if w['word']not in ('<SP>','<AP>')and w['word'].strip()]
 cases.append((case,words,' '.join(w['word']for w in words)))
report={'status':'RESEARCH_ONLY_NOT_RELEASE_READY','runtime':ort.__version__,'device':'Linux CPU, not Android','algorithm':'gigaam-ctc-qa4','frontend':'Arithmetic channel mean + original fixed onnx-asr resampling filters; source/native timeline not measured on Android','unit':'one complete short singing phrase','dataset_revision':manifest['revision'],'excluded':excluded,'cases':[],'limitations':'15 unique short singing clips, one Russian alto; not full-song/genre/long-track/Android acceptance. No posthoc thresholds or offset corrections.'}
for case,words,phrase in cases:
 lp=emissions(case);wrong=next(other for c,_,other in cases if c['id']!=case['id']and distance(normalized(phrase),normalized(other))/max(1,len(normalized(phrase)))>.6)
 onset=align(lp,phrase);row={'id':case['id'],'source_sha256':case['wav_sha256'],'accepted':onset is not None,'foreign_accepted':align(lp,wrong)is not None,'reference_onset_ms':words[0]['start_time']*1000}
 if onset is not None:row.update(onset_ms=onset,signed_error_ms=onset-row['reference_onset_ms'])
 report['cases'].append(row);args.out.write_text(json.dumps(report,indent=2));print(case['id'],'accepted',row['accepted'],'foreign',row['foreign_accepted'],'error_ms',row.get('signed_error_ms'),flush=True)
errors=[abs(c['signed_error_ms'])for c in report['cases']if c['accepted']]
report['summary']={'accepted':len(errors),'total':len(cases),'foreign_accepted':sum(c['foreign_accepted']for c in report['cases']),'median_abs_ms':float(np.median(errors))if errors else None,'p90_abs_ms':float(np.percentile(errors,90))if errors else None,'max_abs_ms':max(errors)if errors else None,'within500_count':sum(e<=500 for e in errors)}
args.out.write_text(json.dumps(report,indent=2));print(report['summary'],flush=True)
