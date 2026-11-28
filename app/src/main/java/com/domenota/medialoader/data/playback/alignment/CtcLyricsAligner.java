package com.domenota.medialoader.data.playback.alignment;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/** Bounded, local CTC alignment. Conservative experimental QA contract, not calibrated probability.
 * All lines pass or no timing is returned. The caller retains the unmodified original text.
 * Alpha + Viterbi backpointers use at most 120 MB; cancellation is checked every frame. */
public final class CtcLyricsAligner {
  public static final String REVISION = "gigaam-ctc-qa2";
  public static final long MAX_CELLS = 24_000_000L;
  private static final int BLANK=33;
  private static final double NEG=Double.NEGATIVE_INFINITY;
  private static final String LETTERS="▁абвгдежзийклмнопрстуфхцчшщъыьэюя";
  private CtcLyricsAligner() {}
  public static final class Result {
    public final long[] onsetsMs;
    public final double globalCer;
    Result(long[] onsets,double cer){onsetsMs=onsets;globalCer=cer;}
  }
  public static String normalize(String line) {
    String text=Normalizer.normalize(line,Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replace('ё','е');
    StringBuilder out=new StringBuilder();
    for(int i=0;i<text.length();i++) {
      char c=text.charAt(i);int id=LETTERS.indexOf(c);
      if(id>0)out.append(c);
      else if(Character.isWhitespace(c)||Character.isSpaceChar(c)||isPunctuation(c)) {
        if(out.length()>0 && out.charAt(out.length()-1)!='▁')out.append('▁');
      } else return null;
    }
    if(out.length()>0 && out.charAt(out.length()-1)=='▁')out.setLength(out.length()-1);
    return out.toString();
  }
  private static boolean isPunctuation(char c) {
    int t=Character.getType(c);
    return t==Character.CONNECTOR_PUNCTUATION || t==Character.DASH_PUNCTUATION ||
      t==Character.START_PUNCTUATION || t==Character.END_PUNCTUATION ||
      t==Character.INITIAL_QUOTE_PUNCTUATION || t==Character.FINAL_QUOTE_PUNCTUATION ||
      t==Character.OTHER_PUNCTUATION;
  }
  private static double add(double a,double b) {
    if(a==NEG)return b;if(b==NEG)return a;
    double m=Math.max(a,b);return m+Math.log1p(Math.exp(-Math.abs(a-b)));
  }
  private static int distance(String a,String b,Runnable check) {
    int[] prev=new int[b.length()+1],cur=new int[b.length()+1];
    for(int j=0;j<prev.length;j++)prev[j]=j;
    for(int i=0;i<a.length();i++) {
      check.run();cur[0]=i+1;
      for(int j=0;j<b.length();j++)cur[j+1]=Math.min(Math.min(cur[j]+1,prev[j+1]+1),prev[j]+(a.charAt(i)==b.charAt(j)?0:1));
      int[] tmp=prev;prev=cur;cur=tmp;
    }
    return prev[b.length()];
  }
  public static Result align(float[][] p,String[] lines,Runnable check) {
    check.run();
    if(p.length==0 || p.length>15000 || lines.length==0 || lines.length>500)return null;
    ArrayList<Integer> seq=new ArrayList<>();int[] first=new int[lines.length],end=new int[lines.length];
    StringBuilder reference=new StringBuilder();
    for(int k=0;k<lines.length;k++) {
      String s=normalize(lines[k]);if(s==null||s.isEmpty())return null;
      if(k>0)seq.add(0);first[k]=seq.size();
      for(int i=0;i<s.length();i++)seq.add(LETTERS.indexOf(s.charAt(i)));
      end[k]=seq.size();reference.append(s.replace("▁",""));
      if(seq.size()>8000)return null;
    }
    int t=p.length,n=seq.size(),states=2*n+1;
    long cells=(long)t*states;if(cells>MAX_CELLS)return null;
    int[] tokens=new int[n],labels=new int[states];boolean[] skip=new boolean[states];
    Arrays.fill(labels,BLANK);
    for(int i=0;i<n;i++){tokens[i]=seq.get(i);labels[2*i+1]=tokens[i];}
    for(int j=2;j<states;j++)skip[j]=labels[j]!=BLANK && labels[j]!=labels[j-2];
    int required=n;for(int i=1;i<n;i++)if(tokens[i]==tokens[i-1])required++;
    if(t<required)return null;
    StringBuilder decoded=new StringBuilder();int prior=-1;
    for(float[] row:p) {
      check.run();if(row.length!=34)return null;double sum=0;int best=0;
      for(int j=0;j<34;j++) {
        if(!Float.isFinite(row[j])||row[j]>1e-5)return null;
        sum+=Math.exp(row[j]);if(row[j]>row[best])best=j;
      }
      if(Math.abs(sum-1)>1e-4)return null;
      if(best!=prior&&best!=BLANK&&best!=0)decoded.append(LETTERS.charAt(best));prior=best;
    }
    double cer=(double)distance(reference.toString(),decoded.toString(),check)/reference.length();
    if(cer>.35)return null;
    long freeHeap=Runtime.getRuntime().maxMemory()-(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
    long needed=cells*5L+(long)lines.length*t*8L+16L*1024*1024;
    if(freeHeap<needed)return null;
    float[] alpha=new float[(int)cells];Arrays.fill(alpha,Float.NEGATIVE_INFINITY);
    byte[] back=new byte[(int)cells];
    double[] prev=new double[states],cur=new double[states];Arrays.fill(prev,NEG);
    prev[0]=alpha[0]=p[0][BLANK];prev[1]=alpha[1]=p[0][tokens[0]];
    for(int i=1;i<t;i++) {
      check.run();int base=i*states,before=base-states;
      for(int j=0;j<states;j++) {
        double a=alpha[before+j],b=j>0?alpha[before+j-1]:NEG,c=j>1&&skip[j]?alpha[before+j-2]:NEG;
        alpha[base+j]=(float)(add(add(a,b),c)+p[i][labels[j]]);
        int move=0;double best=prev[j];
        if(j>0&&prev[j-1]>best){move=1;best=prev[j-1];}
        if(j>1&&skip[j]&&prev[j-2]>best){move=2;best=prev[j-2];}
        back[base+j]=(byte)move;cur[j]=best+p[i][labels[j]];
      }
      double[] tmp=prev;prev=cur;cur=tmp;
    }
    int state=prev[states-1]>=prev[states-2]?states-1:states-2;
    if(!Double.isFinite(prev[state]))return null;
    int[] starts=new int[n];Arrays.fill(starts,-1);double[] peak=new double[n];Arrays.fill(peak,NEG);
    for(int i=t-1;i>=0;i--) {
      if((state&1)==1){int tok=state/2;starts[tok]=i;peak[tok]=Math.max(peak[tok],p[i][tokens[tok]]);}
      if(i>0)state-=back[i*states+state];
    }
    for(int k=0;k<lines.length;k++) {
      double sum=0;int count=0;
      for(int j=first[k];j<end[k];j++)if(tokens[j]!=0){sum+=peak[j];count++;}
      if(count==0||sum/count< -5 || starts[first[k]]<0)return null;
    }
    // Conditional entry posterior: acoustic agreement was checked separately above.
    double z=add(alpha[(t-1)*states+states-1],alpha[(t-1)*states+states-2]);
    if(!Double.isFinite(z))return null;
    double[] beta=new double[states],next=new double[states];Arrays.fill(beta,NEG);beta[states-1]=beta[states-2]=0;
    double[][] mass=new double[lines.length][t];for(double[] row:mass)Arrays.fill(row,NEG);
    for(int i=t-1;i>=0;i--) {
      check.run();
      for(int k=0;k<lines.length;k++) {
        int j=2*first[k]+1;
        if(i==0)mass[k][i]=j==1?p[0][tokens[0]]+beta[1]-z:NEG;
        else {
          double source=alpha[(i-1)*states+j-1];
          if(j>=2&&skip[j])source=add(source,alpha[(i-1)*states+j-2]);
          mass[k][i]=source+p[i][labels[j]]+beta[j]-z;
        }
      }
      if(i>0) {
        for(int j=0;j<states;j++) {
          double b=j+1<states?beta[j+1]+p[i][labels[j+1]]:NEG;
          double c=j+2<states&&skip[j+2]?beta[j+2]+p[i][labels[j+2]]:NEG;
          next[j]=add(add(beta[j]+p[i][labels[j]],b),c);
        }
        double[] tmp=beta;beta=next;next=tmp;
      }
    }
    long[] times=new long[lines.length];
    for(int k=0;k<lines.length;k++) {
      check.run();double max=NEG;for(double x:mass[k])max=Math.max(max,x);
      double sum=0;for(int i=0;i<t;i++){mass[k][i]=Math.exp(mass[k][i]-max);sum+=mass[k][i];}
      if(!Double.isFinite(sum)||sum<=0)return null;
      double cumulative=0;int lo=-1,hi=-1;
      for(int i=0;i<t;i++){cumulative+=mass[k][i]/sum;if(lo<0&&cumulative>=.025)lo=i;if(hi<0&&cumulative>=.975)hi=i;}
      if(lo<0||hi<0||(hi-lo)*40>500 || starts[first[k]]<lo || starts[first[k]]>hi)return null;
      times[k]=starts[first[k]]*40L;
      if(k>0&&times[k]<=times[k-1])return null;
    }
    return new Result(times,cer);
  }
}
