package com.domenota.medialoader.data.playback.alignment;

/** Buffer timestamps must describe the same continuous sample clock as the player.
 * Decline discontinuities instead of silently shifting every later lyric line. */
public final class PcmTimeline {
  private long frames=0,originUs=0;
  private int rate=0;
  public void accept(long ptsUs,int count,int sampleRate) {
    if(count<=0)return;
    if(sampleRate<16000 || sampleRate>384000 || ptsUs< -2000 || ptsUs>601000000L)
      throw new IllegalArgumentException("Unsupported PCM timeline");
    if(rate==0) {
      if(Math.abs(ptsUs)>2000)throw new IllegalArgumentException("Audio starts outside zero timeline");
      rate=sampleRate;originUs=ptsUs;
    }
    if(rate!=sampleRate)throw new IllegalArgumentException("PCM rate changed");
    long expected=originUs+frames*1000000L/rate;
    if(Math.abs(ptsUs-expected)>2000)throw new IllegalArgumentException("Discontinuous PCM timestamps");
    frames+=count;
  }
}
