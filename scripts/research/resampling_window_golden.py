"""Original onnx-asr0.11.0 fixed filters, copyright2025 Ilya Stupakov, MIT.
See docs/research/licenses/onnx-asr-0.11.0-MIT.txt. Fixtures contain synthetic
noise/impulses only. Feed them to compiled ResamplingWindowProbe and the actual
PcmResamplingWindow Java class; compares every source and output sample.
"""
import pathlib,struct,json,hashlib,argparse,zipfile
import numpy as np,onnxruntime as ort
parser=argparse.ArgumentParser(description='Synthetic original-ORT resampling golden fixtures; no user media.')
parser.add_argument('--assets',type=pathlib.Path,required=True,help='Verified onnx_asr.whl')
parser.add_argument('--out',type=pathlib.Path,required=True)
args=parser.parse_args();root=args.assets;out=args.out;out.mkdir(exist_ok=True)
assert hashlib.sha256((root/'onnx_asr.whl').read_bytes()).hexdigest()=='142d8b3ce7716684992826a269304f5ce9cf1c0fe704b751358e223f45d2a5cf'
originals=out/'originals';originals.mkdir(exist_ok=True)
with zipfile.ZipFile(root/'onnx_asr.whl')as wheel:
 for rate in [8000,11025,22050,24000,32000,44100,48000]:
  name='resample_'+str(rate//1000)+'_16.onnx'
  (originals/name).write_bytes(wheel.read('onnx_asr/preprocessors/data/'+name))
assert ort.__version__=='1.20.0' 
opts=ort.SessionOptions();opts.intra_op_num_threads=1;opts.inter_op_num_threads=1
report=[]
for rate in [8000,11025,22050,24000,32000,44100,48000]:
 session=ort.InferenceSession(str(originals/('resample_'+str(rate//1000)+'_16.onnx')),opts,providers=['CPUExecutionProvider'])
 rng=np.random.default_rng(17238);source=rng.normal(0,.1,rate*3+17).astype(np.float32)
 source[rate-1:rate+2]=[.5,-.5,.5];source[rate*2+rate//25]=1
 def run(x):
  y,n=session.run(None,{'waveforms':x[None],'waveforms_lens':np.array([len(x)],np.int64)});assert y.shape[1]==n[0]and np.isfinite(y).all();return y[0]
 whole=run(source);chunks=[]
 for start in range(0,len(source),rate):
  left=max(0,start-rate//25);right=min(len(source),start+rate+rate//25);x=source[left:right].copy();chunks.append((x,run(x)))
 file=out/(str(rate)+'.bin')
 with file.open('wb')as f:
  f.write(struct.pack('>ii',rate,len(source)));f.write(source.astype('>f4').tobytes());f.write(struct.pack('>i',len(chunks)))
  for x,y in chunks:
   f.write(struct.pack('>i',len(x)));f.write(x.astype('>f4').tobytes());f.write(struct.pack('>i',len(y)));f.write(y.astype('>f4').tobytes())
  f.write(struct.pack('>i',len(whole)));f.write(whole.astype('>f4').tobytes())
 report.append({'rate':rate,'source_frames':len(source),'output_frames':len(whole),'chunks':len(chunks),'fixture_sha256':hashlib.sha256(file.read_bytes()).hexdigest()})
(out/'manifest.json').write_text(json.dumps(report,indent=2))
print('Pinned ORT'+ort.__version__+' golden fixtures generated for7 rates; synthetic, not Android')
