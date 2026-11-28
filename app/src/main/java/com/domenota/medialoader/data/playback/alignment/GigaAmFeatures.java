package com.domenota.medialoader.data.playback.alignment;

/** Exact 320-point transform (5 x 64), original publisher window and mel matrix.
 * Caller provides mono 16 kHz PCM and verified filters. Never pads FFT to 512. */
public final class GigaAmFeatures {
  private final float[] window,mel;
  private final double[][] cosine=new double[161][5],sine=new double[161][5];
  public GigaAmFeatures(float[] window,float[] mel) {
    if(window.length!=320||mel.length!=161*64)throw new IllegalArgumentException("filters");
    this.window=window.clone();this.mel=mel.clone();
    for(int k=0;k<=160;k++)for(int r=0;r<5;r++) {
      double angle=-2*Math.PI*k*r/320;cosine[k][r]=Math.cos(angle);sine[k][r]=Math.sin(angle);
    }
  }
  public float[][] extract(float[] samples,Runnable check) {
    if(samples.length<320||samples.length>26*16000+320)throw new IllegalArgumentException("PCM window");
    int frames=1+(samples.length-320)/160;
    float[][] features=new float[64][frames];
    double[][] re=new double[5][64],im=new double[5][64];float[] power=new float[161];
    for(int t=0;t<frames;t++) {
      check.run();
      for(int r=0;r<5;r++) {
        for(int m=0;m<64;m++){int n=5*m+r;float x=samples[t*160+n]*window[n];if(!Float.isFinite(x))throw new IllegalArgumentException("PCM");re[r][m]=x;im[r][m]=0;}
        fft64(re[r],im[r]);
      }
      for(int k=0;k<=160;k++) {
        int q=k%64;double a=0,b=0;
        for(int r=0;r<5;r++){a+=re[r][q]*cosine[k][r]-im[r][q]*sine[k][r];b+=re[r][q]*sine[k][r]+im[r][q]*cosine[k][r];}
        float magnitude=(float)Math.hypot(a,b);power[k]=magnitude*magnitude;
      }
      for(int m=0;m<64;m++) {
        float sum=0;for(int k=0;k<=160;k++)sum+=power[k]*mel[k*64+m];
        features[m][t]=(float)Math.log(Math.max(1e-9,Math.min(1e9,sum)));
      }
    }
    return features;
  }
  private static void fft64(double[] re,double[] im) {
    for(int i=1,j=0;i<64;i++) {
      int bit=32;for(; (j&bit)!=0;bit>>=1)j^=bit;j^=bit;
      if(i<j){double a=re[i];re[i]=re[j];re[j]=a;a=im[i];im[i]=im[j];im[j]=a;}
    }
    for(int len=2;len<=64;len*=2) {
      double theta=-2*Math.PI/len,cr=Math.cos(theta),ci=Math.sin(theta);
      for(int start=0;start<64;start+=len) {
        double wr=1,wi=0;
        for(int j=0;j<len/2;j++) {
          int a=start+j,b=a+len/2;double xr=re[b]*wr-im[b]*wi,xi=re[b]*wi+im[b]*wr;
          re[b]=re[a]-xr;im[b]=im[a]-xi;re[a]+=xr;im[a]+=xi;
          double next=wr*cr-wi*ci;wi=wr*ci+wi*cr;wr=next;
        }
      }
    }
  }
}
