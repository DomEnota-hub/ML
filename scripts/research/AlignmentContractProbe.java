import com.domenota.medialoader.data.playback.alignment.CtcLyricsAligner;
import java.util.Arrays;

/** Synthetic contract only. Also accepts local binary emissions; never prints lyrics. */
public final class AlignmentContractProbe {
  static void check(boolean ok) { if (!ok) throw new AssertionError(); }
  static float[][] peaks(int... labels) {
    float[][] p = new float[labels.length][34];
    for (int t=0;t<labels.length;t++) {
      Arrays.fill(p[t],(float)Math.log(.01/33)); p[t][labels[t]]=(float)Math.log(.99);
    }
    return p;
  }
  public static void main(String[] args) throws Exception {
    if(args.length>0) {
      try(java.io.DataInputStream in=new java.io.DataInputStream(new java.io.FileInputStream(args[0]))) {
        int t=in.readInt(); float[][] p=new float[t][34];
        for(float[] row:p)for(int v=0;v<34;v++)row[v]=in.readFloat();
        int n=in.readInt();String[] lines=new String[n];for(int i=0;i<n;i++)lines[i]=in.readUTF();
        long now=System.nanoTime(); CtcLyricsAligner.Result r=CtcLyricsAligner.align(p,lines,()->{});
        System.out.println(r==null?"DECLINED":"ACCEPTED "+Arrays.toString(r.onsetsMs));
        System.out.println("elapsed_ms="+(System.nanoTime()-now)/1_000_000);return;
      }
    }
    CtcLyricsAligner.Result r=CtcLyricsAligner.align(peaks(33,1,33,1,33,0,33,2,33),new String[]{"аа","б"},()->{});
    check(r!=null && Arrays.equals(r.onsetsMs,new long[]{40,280}));
    check(CtcLyricsAligner.align(peaks(33,33,33,33),new String[]{"а"},()->{})==null);
    check(CtcLyricsAligner.align(peaks(33,1,33,2,33),new String[]{"я"},()->{})==null);
    check(CtcLyricsAligner.align(peaks(1,1),new String[]{"аа"},()->{})==null);
    check(CtcLyricsAligner.align(peaks(1,33),new String[]{"hello"},()->{})==null);
    boolean cancelled=false;try{CtcLyricsAligner.align(peaks(33,1,33),new String[]{"а"},()->{throw new IllegalStateException("cancel");});}catch(IllegalStateException e){cancelled=true;}
    check(cancelled);
    float[][] bad=peaks(1,33);bad[0][0]=Float.NaN;
    check(CtcLyricsAligner.align(bad,new String[]{"а"},()->{})==null);
    System.out.println("CTC contract: 7 scenarios PASS; synthetic, not Android/music calibration");
  }
}
