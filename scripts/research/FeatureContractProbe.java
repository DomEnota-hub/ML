import com.domenota.medialoader.data.playback.alignment.GigaAmFeatures;
import java.io.*;
/** Input is a local deterministic PCM/filter/NumPy golden fixture, never a song. */
public final class FeatureContractProbe {
  public static void main(String[] args) throws Exception {
    try(DataInputStream in=new DataInputStream(new FileInputStream(args[0]))) {
      float[] window=new float[320],mel=new float[161*64],samples=new float[640];
      for(int i=0;i<window.length;i++)window[i]=in.readFloat();
      for(int i=0;i<mel.length;i++)mel[i]=in.readFloat();
      for(int i=0;i<samples.length;i++)samples[i]=in.readFloat();
      float[][] result=new GigaAmFeatures(window,mel).extract(samples,()->{});
      double max=0;
      for(int m=0;m<64;m++)for(int t=0;t<3;t++)max=Math.max(max,Math.abs(result[m][t]-in.readFloat()));
      if(max>1e-4)throw new AssertionError("feature error "+max);
      System.out.println("original-filter FFT320 feature golden PASS; max_abs="+max);
    }
  }
}
