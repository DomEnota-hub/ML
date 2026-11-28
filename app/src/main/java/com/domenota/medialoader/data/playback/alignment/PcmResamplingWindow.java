package com.domenota.medialoader.data.playback.alignment;

import java.util.Arrays;

/** Bounded core/context scheduling for the original fixed ORT resampling filters.
 * Native source rates and every window start lie on an exact 40ms phase.
 * Context provides filter support; it never adds delay to emitted sample times. */
public final class PcmResamplingWindow {
  public interface Engine { float[] resample(float[] input); }
  public interface Sink { void add(float sample); }
  private final int rate,core,context;
  private final float[] buffer;
  private final Engine engine;
  private final Sink sink;
  private final Runnable check;
  private int size;
  private long total,left,nextCore;
  private boolean finished;
  public PcmResamplingWindow(int rate,Engine engine,Sink sink,Runnable check) {
    if(rate<8000 || rate>48000 || rate%25!=0)throw new IllegalArgumentException("Unsupported resampling phase");
    this.rate=rate;core=rate;context=rate/25;
    buffer=new float[core+2*context];this.engine=engine;this.sink=sink;this.check=check;
  }
  public void accept(float value) {
    if(finished || !Float.isFinite(value))throw new IllegalArgumentException("Invalid PCM stream");
    if(size>=buffer.length)throw new IllegalStateException("Resampling buffer exceeded");
    buffer[size++]=value;total++;
    drain(false);
  }
  public void finish() {
    if(finished)return;
    finished=true;drain(true);
  }
  private void drain(boolean end) {
    while(nextCore<total && (end || total>=nextCore+core+context)) {
      check.run();
      long stop=Math.min(nextCore+core,total);
      float[] output=engine.resample(Arrays.copyOf(buffer,size));
      check.run();
      long base=left*16000/rate;
      int from=(int)(nextCore*16000/rate-base);
      int until=(int)((stop*16000+rate-1)/rate-base);
      long expected=((long)size*16000+rate-1)/rate;
      if(output.length!=expected || from<0 || until>output.length || from>until)
        throw new IllegalStateException("Resampling timeline mismatch");
      for(int i=from;i<until;i++) {
        if(!Float.isFinite(output[i]))throw new IllegalArgumentException("Invalid resampled PCM");
        sink.add(output[i]);
      }
      nextCore+=core;
      if(end && nextCore>=total){size=0;left=total;return;}
      long newLeft=nextCore-context;
      int remove=(int)(newLeft-left);
      if(remove<0 || remove>size)throw new IllegalStateException("Invalid resampling cursor");
      System.arraycopy(buffer,remove,buffer,0,size-remove);
      size-=remove;left=newLeft;
    }
  }
}
