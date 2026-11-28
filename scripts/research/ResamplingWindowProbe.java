import com.domenota.medialoader.data.playback.alignment.PcmResamplingWindow;
import java.io.*;

/** Synthetic original-ORT golden fixtures, generated locally; no song data. */
public final class ResamplingWindowProbe {
  public static void main(String[] args) throws Exception {
    for(String file:args)try(DataInputStream in=new DataInputStream(new FileInputStream(file))) {
      int rate=in.readInt(),count=in.readInt();float[] source=new float[count];
      for(int i=0;i<count;i++)source[i]=in.readFloat();
      int chunks=in.readInt();int[] calls={0},at={0};
      float[] actual=new float[(int)(((long)count*16000+rate-1)/rate)];
      PcmResamplingWindow stream=new PcmResamplingWindow(rate,input->{
        try {
          if(input.length!=in.readInt())throw new AssertionError("Source window length");
          for(float value:input)if(Float.floatToIntBits(value)!=Float.floatToIntBits(in.readFloat()))throw new AssertionError("Source phase/overlap");
          float[] out=new float[in.readInt()];for(int j=0;j<out.length;j++)out[j]=in.readFloat();
          calls[0]++;return out;
        }catch(IOException e){throw new UncheckedIOException(e);}
      },value->actual[at[0]++]=value,()->{});
      for(float value:source)stream.accept(value);
      stream.finish();stream.finish();
      if(calls[0]!=chunks || at[0]!=actual.length || actual.length!=in.readInt())throw new AssertionError("Output count");
      double max=0;for(float value:actual)max=Math.max(max,Math.abs(value-in.readFloat()));
      if(max>2e-6 || in.read()!=-1)throw new AssertionError("Golden mismatch: "+max);
      System.out.println("rate="+rate+" frames="+actual.length+" chunks="+calls[0]+" max_abs="+max+" PASS");
    }
  }
}
