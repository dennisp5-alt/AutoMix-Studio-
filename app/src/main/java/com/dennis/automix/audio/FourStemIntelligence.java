package com.dennis.automix.audio;

import java.io.IOException;
import java.util.*;

/**
 * Conservative, deterministic four-stem analysis for offline premaster mixing.
 *
 * Decisions are made only from co-active passages. Whole-song RMS would
 * over-correct vocals during verses with instrumental-only sections.
 * No model inference, phase manipulation, dynamics compression or widening.
 */
public final class FourStemIntelligence {
    private FourStemIntelligence() {}

    public static final class Plan {
        public final boolean recognized;
        public final double[] gainDb, eqDb;
        public final double[] eqHz;
        public final double coactiveSeconds, maskingDb;
        public final String note;
        Plan(boolean recognized, double[] gainDb, double[] eqDb, double[] eqHz,
             double coactiveSeconds, double maskingDb, String note) {
            this.recognized=recognized;
            this.gainDb=gainDb;
            this.eqDb=eqDb;
            this.eqHz=eqHz;
            this.coactiveSeconds=coactiveSeconds;
            this.maskingDb=maskingDb;
            this.note=note;
        }
    }

    public static Plan inspect(List<AutoMixEngine.Track> tracks,
                               List<AutoMixEngine.WavReader> readers,
                               double[] activeRms, int rate) throws IOException {
        if(tracks.size()!=4 || readers.size()!=4 || activeRms.length!=4)
            throw new IOException("Four-stem inspection requires exactly four tracks.");
        double[] gain=new double[4],eq=new double[4],hz=new double[4];
        int vocal=-1,drums=-1,bass=-1,other=-1;
        for(int i=0;i<4;i++) {
            String n=tracks.get(i).name.toLowerCase(Locale.ROOT);
            int role;
            if(n.contains("vocal")||n.contains("voice")||n.contains("vox"))role=0;
            else if(n.contains("drum")||n.contains("perc"))role=1;
            else if(n.contains("bass")||n.contains("sub"))role=2;
            else if(n.contains("other")||n.contains("instruments")||n.contains("music"))role=3;
            else role=-1;
            if(role==0){if(vocal>=0)return unknown(gain,eq,hz);vocal=i;}
            if(role==1){if(drums>=0)return unknown(gain,eq,hz);drums=i;}
            if(role==2){if(bass>=0)return unknown(gain,eq,hz);bass=i;}
            if(role==3){if(other>=0)return unknown(gain,eq,hz);other=i;}
        }
        if(vocal<0 || drums<0 || bass<0 || other<0)
            return unknown(gain,eq,hz);
        int block=4096;
        float[] vb=new float[block*2],ob=new float[block*2];
        AutoMixEngine.WavReader vr=readers.get(vocal),or=readers.get(other);
        vr.rewind();or.rewind();
        Bandpass vf=new Bandpass(rate,2500.0,1.0);
        Bandpass of=new Bandpass(rate,2500.0,1.0);
        double vocalPower=0,otherPower=0,vocalMid=0,otherMid=0;
        long coactiveFrames=0;
        long maxFrames=Math.max(vr.frames,or.frames);
        long progressed=0;
        while(progressed<maxFrames){
            if(Thread.currentThread().isInterrupted()) throw new IOException("Four-stem analysis cancelled.");
            int nv=vr.read(vb,block),no=or.read(ob,block);
            int count=Math.max(nv,no);
            if(count<=0) break;
            double vPower=0,oPower=0,vBand=0,oBand=0;
            for(int f=0;f<count;f++){
                double v=f<nv?(vb[f*vr.channels]+(vr.channels==1?0:vb[f*2+1]))/(vr.channels==1?1:2):0;
                double o=f<no?(ob[f*or.channels]+(or.channels==1?0:ob[f*2+1]))/(or.channels==1?1:2):0;
                double vm=vf.process(v),om=of.process(o);
                vPower+=v*v;
                oPower+=o*o;
                vBand+=vm*vm;
                oBand+=om*om;
            }
            double vrms=Math.sqrt(vPower/count),orms=Math.sqrt(oPower/count);
            // Both sources must be materially active to count as masking evidence.
            if(vrms>=Math.max(.0015,activeRms[vocal]*.30) &&
               orms>=Math.max(.0015,activeRms[other]*.30)){
                coactiveFrames+=count;
                vocalPower+=vPower;otherPower+=oPower;
                vocalMid+=vBand;otherMid+=oBand;
            }
            progressed+=count;
        }
        double concurrentSeconds=coactiveFrames/(double)rate;
        double maskingDb=0;
        String note;
        if(coactiveFrames>=rate && coactiveFrames>=maxFrames*.03 &&
           vocalPower>1e-9 && otherPower>1e-9){
            double ratio=Math.sqrt(otherPower/vocalPower);
            if(ratio>2.0)gain[other]=-.90;
            else if(ratio>1.4)gain[other]=-.60;
            else if(ratio>1.15)gain[other]=-.30;
            if(vocalMid/coactiveFrames>1e-6 &&
               otherMid/coactiveFrames>1e-6){
                maskingDb=10*Math.log10(otherMid/vocalMid);
                if(maskingDb>=-1.5){
                    eq[other]=-.75;
                    hz[other]=2500.0;
                }
            }
            note=String.format(Locale.US,
                 "Co-active vocal/other analysis %.1fs; other level trim %.2fdB; 2.5kHz EQ %.2fdB. All changes bounded and optional.",
                 concurrentSeconds,gain[other],eq[other]);
        }else{
            note="Insufficient co-active vocal/other evidence; four-stem source levels and tone preserved.";
        }
        return new Plan(true,gain,eq,hz,concurrentSeconds,maskingDb,note);
    }

    private static Plan unknown(double[] g,double[] e,double[] h){
        return new Plan(false,g,e,h,0,0,
          "Four unique roles (vocals, drums, bass, other) not detected in filenames; preserving original balance.");
    }

    /** Stereo-channel-independent RBJ peaking EQ for bounded subtractive masking relief. */
    public static final class Bell {
        private final double b0,b1,b2,a1,a2;
        private double x1,x2,y1,y2;
        public Bell(int rate,double center,double q,double db){
            if(rate<8000||center<=0||center>=rate/2.0||q<=0)
                throw new IllegalArgumentException("Invalid peaking EQ settings");
            double a=Math.pow(10,db/40.0),w=2*Math.PI*center/rate;
            double alpha=Math.sin(w)/(2*q),cos=Math.cos(w),a0=1+alpha/a;
            b0=(1+alpha*a)/a0;
            b1=(-2*cos)/a0;
            b2=(1-alpha*a)/a0;
            a1=(-2*cos)/a0;
            a2=(1-alpha/a)/a0;
        }
        public double process(double x){
            double y=b0*x+b1*x1+b2*x2-a1*y1-a2*y2;
            x2=x1;x1=x;y2=y1;y1=y;
            return y;
        }
    }

    private static final class Bandpass {
        final double b0,b2,a1,a2;
        double x1,x2,y1,y2;
        Bandpass(int rate,double hz,double q){
            double omega=2*Math.PI*hz/rate,alpha=Math.sin(omega)/(2*q),a0=1+alpha;
            b0=alpha/a0;b2=-alpha/a0;
            a1=-2*Math.cos(omega)/a0;a2=(1-alpha)/a0;
        }
        double process(double x){
            double y=b0*x+b2*x2-a1*y1-a2*y2;
            x2=x1;x1=x;y2=y1;y1=y;
            return y;
        }
    }
}
