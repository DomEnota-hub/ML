import wave, math, random, array, subprocess
for seed in [17,83]:
 r=random.Random(seed); out=array.array('h'); rate=8000
 notes=[r.uniform(180,1700) for _ in range(80)]
 for i in range(rate*32):
  t=i/rate; k=int(t*2); f=notes[k]; u=t*2-k
  x=(math.sin(2*math.pi*f*t)+.4*math.sin(2*math.pi*f*1.499*t)+.2*math.sin(2*math.pi*notes[(k+9)%80]*t))*(.3+.6*math.sin(math.pi*u)**2)
  out.append(int(10500*x))
 with wave.open(f'/tmp/qa188-audio/{seed}.wav','wb') as w: w.setparams((1,2,rate,0,'NONE','none')); w.writeframes(out.tobytes())
for fmt,opts in [('mp3',['-c:a','libmp3lame','-b:a','64k']),('m4a',['-c:a','aac','-b:a','96k']),('ogg',['-c:a','libvorbis','-q:a','4'])]:
 subprocess.run(['ffmpeg','-v','error','-y','-i','/tmp/qa188-audio/17.wav',*opts,f'/tmp/qa188-audio/reencoded.{fmt}'],check=True)
 subprocess.run(['ffmpeg','-v','error','-y','-i',f'/tmp/qa188-audio/reencoded.{fmt}','-f','f32le','-ar','8000','-ac','1',f'/tmp/qa188-audio/{fmt}.pcm'],check=True)
for name,src,trim in [('original','17',False),('negative','83',False),('trim','17',True)]:
 subprocess.run(['ffmpeg','-v','error','-y','-i',f'/tmp/qa188-audio/{src}.wav',*(['-ss','3.173','-t','22.6'] if trim else []),'-f','f32le','-ar','8000','-ac','1',f'/tmp/qa188-audio/{name}.pcm'],check=True)
