package com.dennis.automix.audio;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.Locale;

/**
 * Read-only analysis plus optional ultra-conservative stereo premaster preparation.
 * Not a mastering processor. No compressor, limiter, widening, normalisation or
 * automatic alteration of already heavily-limited audio. Uses the v0.2.3 WAV decoder.
 */
public final class StereoPremasterEngine {
    private static final int BLOCK = 4096;
    private static final double HEADROOM = Math.pow(10, -6.0 / 20.0);
    private StereoPremasterEngine() {}

    public interface Progress { void update(int percent, String message); }
    public static final class Report {
        public final File output;
        public final long frames;
        public final int rate, inputBits;
        public final double inputPeakDbfs, inputRmsDbfs, crestDb, correlation, monoLossDb;
        public final double dcLeft, dcRight, lowMidExcessDb, eqAppliedDb, outputGainDb, outputPeakDbfs;
        public final boolean dcCorrected, questionableMono, likelyPrelimited;
        public final String decision;
        Report(File output, long frames, int rate, int bits, Metrics m,
               double lowMidDb, boolean removeDC, double eqDb, double gainDb,
               double finishedPeak, String decision) {
            this.output = output; this.frames=frames;this.rate=rate;this.inputBits=bits;
            inputPeakDbfs=db(m.peak);inputRmsDbfs=db(m.rms());
            crestDb=inputPeakDbfs-inputRmsDbfs;
            correlation=m.correlation(); monoLossDb=m.monoLossDb();
            dcLeft=m.sumL/frames;dcRight=m.sumR/frames;
            lowMidExcessDb=lowMidDb;eqAppliedDb=eqDb;
            outputGainDb=gainDb;outputPeakDbfs=db(finishedPeak);
            dcCorrected=removeDC;questionableMono=monoLossDb < -3.0;
            likelyPrelimited=crestDb<8.5;
            this.decision=decision;
        }
        public String summary() {
            return String.format(Locale.US,
                "Source: %d-bit, %d Hz, %.1f s\n"+
                "Peak %.2f dBFS | RMS %.2f dBFS | crest %.1f dB\n"+
                "Stereo correlation %.3f | mono change %.2f dB\n"+
                "DC L %.4f R %.4f | low-mid band ratio %+.1f dB\n"+
                "Preparation: %s\n"+
                "Tone adjustment %+.2f dB | shared gain %+.2f dB\n"+
                "Rendered sample peak %.2f dBFS (NOT true peak)\n%s",
                inputBits,rate,frames/(double)rate,inputPeakDbfs,inputRmsDbfs,crestDb,
                correlation,monoLossDb,dcLeft,dcRight,lowMidExcessDb,
                dcCorrected?"DC offset removed":"No DC correction",eqAppliedDb,outputGainDb,
                outputPeakDbfs,decision);
        }
        public String json() {
            return String.format(Locale.US,
               "{\n  \"format\":\"Stereo Premaster QC v0.2.4\",\n"+
               "  \"frames\":%d, \"sample_rate\":%d, \"source_bit_depth\":%d,\n"+
               "  \"input_sample_peak_dbfs\":%.5f, \"input_rms_dbfs\":%.5f, "+
               "\"crest_db\":%.5f,\n"+
               "  \"stereo_correlation\":%.6f, \"mono_level_change_db\":%.5f,\n"+
               "  \"dc_left\":%.7f, \"dc_right\":%.7f, \"lowmid_band_ratio_db\":%.5f,\n"+
               "  \"dc_removed\":%s, \"eq_db\":%.3f, \"headroom_gain_db\":%.5f,\n"+
               "  \"output_sample_peak_dbfs\":%.5f, \"mono_warning\":%s, "+
               "\"likely_prelimited\":%s,\n"+
               "  \"true_peak_measured\":false, \"lufs_measured\":false,\n"+
               "  \"note\":\"Sample-peak-controlled premaster; no compression, limiting, "+
               "loudness normalisation, or stereo manipulation.\"\n}\n",
               frames,rate,inputBits,inputPeakDbfs,inputRmsDbfs,crestDb,
               correlation,monoLossDb,dcLeft,dcRight,lowMidExcessDb,
               dcCorrected,eqAppliedDb,outputGainDb,outputPeakDbfs,
               questionableMono,likelyPrelimited);
        }
    }
    private static class Metrics {
        long frames;
        double peak, energy, sumL,sumR,energyL,energyR,cross,monoEnergy,lowMid,upperMid;
        double rms(){return frames==0?0:Math.sqrt(energy/(2.0*frames));}
        double correlation(){return energyL*energyR<1e-20?0:cross/Math.sqrt(energyL*energyR);}
        double monoLossDb(){return energy<1e-20?0:10*Math.log10(Math.max(1e-20,monoEnergy/(energy*.5)));}
    }
    private static class Bell {
        final double b0,b1,b2,a1,a2;double x1,x2,y1,y2;
        Bell(int sr,double hz,double q,double db) {
            double a=Math.pow(10,db/40.0),w=2*Math.PI*hz/sr,
                   cos=Math.cos(w),alpha=Math.sin(w)/(2*q),a0=1+alpha/a;
            b0=(1+alpha*a)/a0;b1=(-2*cos)/a0;b2=(1-alpha*a)/a0;
            a1=(-2*cos)/a0;a2=(1-alpha/a)/a0;
        }
        double tick(double x){
            double y=b0*x+b1*x1+b2*x2-a1*y1-a2*y2;
            x2=x1;x1=x;y2=y1;y1=y;return y;
        }
    }
    private static class Band {
        final double b0,b2,a1,a2;double x1,x2,y1,y2;
        Band(int sr,double hz){
            double w=2*Math.PI*hz/sr,alpha=Math.sin(w)/(2*0.80),a0=1+alpha;
            b0=alpha/a0;b2=-alpha/a0;a1=-2*Math.cos(w)/a0;a2=(1-alpha)/a0;
        }
        double tick(double x){
            double y=b0*x+b2*x2-a1*y1-a2*y2;
            x2=x1;x1=x;y2=y1;y1=y;return y;
        }
    }
    private static double db(double magnitude) {
        return 20*Math.log10(Math.max(1.0e-12,magnitude));
    }
    private static void cancelled() throws IOException {
        if(Thread.currentThread().isInterrupted())throw new IOException("Premaster interrupted");
    }
    private static void notify(Progress cb,int n,String msg){if(cb!=null)cb.update(n,msg);}
    private static Metrics analyse(AutoMixEngine.WavReader source, Progress cb)throws IOException {
        Metrics m=new Metrics();
        Band[] low={new Band(source.sampleRate,280),new Band(source.sampleRate,280)};
        Band[] upper={new Band(source.sampleRate,1350),new Band(source.sampleRate,1350)};
        float[] data=new float[BLOCK*2];
        int n;long done=0;
        source.rewind();
        while((n=source.read(data,BLOCK))>0){
            cancelled();
            for(int i=0;i<n;i++){
                double l=data[i*2],r=data[i*2+1];
                if(!Double.isFinite(l)||!Double.isFinite(r))throw new IOException("Non-finite audio sample");
                m.peak=Math.max(m.peak,Math.max(Math.abs(l),Math.abs(r)));
                m.energy+=l*l+r*r;m.energyL+=l*l;m.energyR+=r*r;m.cross+=l*r;
                m.sumL+=l;m.sumR+=r;
                double sum=l+r;m.monoEnergy+=.25*sum*sum;
                double loL=low[0].tick(l),loR=low[1].tick(r);
                double hiL=upper[0].tick(l),hiR=upper[1].tick(r);
                m.lowMid+=loL*loL+loR*loR;m.upperMid+=hiL*hiL+hiR*hiR;
            }
            done+=n;
            if(cb!=null && done % (BLOCK*64L)<BLOCK)
                notify(cb,(int)Math.min(30,30*done/source.frames),"Analysing stereo WAV");
        }
        m.frames=done;
        if(done==0||m.peak<1e-8)throw new IOException("Empty or silent WAV");
        return m;
    }
    private static double render(AutoMixEngine.WavReader reader,
                                 AutoMixEngine.WavWriter writer,
                                 double dcL,double dcR,double eqDb,double gain,
                                 Progress cb,int start,int stop) throws IOException {
        Bell l=eqDb<0?new Bell(reader.sampleRate,280,0.85,eqDb):null;
        Bell r=eqDb<0?new Bell(reader.sampleRate,280,0.85,eqDb):null;
        float[] input=new float[BLOCK*2],mixed=new float[BLOCK*2];
        reader.rewind();long done=0;double peak=0;int n;
        while((n=reader.read(input,BLOCK))>0){
            cancelled();
            for(int i=0;i<n;i++){
                double left=input[i*2]-dcL,right=input[i*2+1]-dcR;
                if(l!=null){left=l.tick(left);right=r.tick(right);}
                left*=gain;right*=gain;
                if(!Double.isFinite(left)||!Double.isFinite(right))
                    throw new IOException("Premaster produced invalid samples");
                peak=Math.max(peak,Math.max(Math.abs(left),Math.abs(right)));
                if(writer!=null){mixed[i*2]=(float)left;mixed[i*2+1]=(float)right;}
            }
            if(writer!=null)writer.write(mixed,n);
            done+=n;
            if(cb!=null && done % (BLOCK*64L)<BLOCK)
                notify(cb,start+(int)((stop-start)*done/reader.frames),"Preparing stereo premaster");
        }
        return peak;
    }

    public static Report prepare(File input,File output,Progress cb) throws IOException {
        if(input==null||output==null)throw new IOException("Input and output are required");
        if(input.getCanonicalFile().equals(output.getCanonicalFile()))
            throw new IOException("Output must not overwrite your original WAV");
        File parent=output.getAbsoluteFile().getParentFile();
        if(parent!=null&&!parent.exists()&&!parent.mkdirs())
            throw new IOException("Cannot create output folder");
        File stage=new File(output.getAbsolutePath()+".incomplete");
        if(stage.exists()&&!stage.delete())throw new IOException("Old incomplete file cannot be removed");
        try(AutoMixEngine.WavReader reader=new AutoMixEngine.WavReader(input)) {
            if(reader.channels!=2)throw new IOException("Select a stereo WAV (two channels)");
            if(reader.frames*6L>0xFFFFFFFFL-36L)
                throw new IOException("Premaster exceeds standard 4 GB WAV size");
            Metrics m=analyse(reader,cb);
            final double meanL=m.sumL/m.frames,meanR=m.sumR/m.frames;
            boolean dc=Math.max(Math.abs(meanL),Math.abs(meanR))>.004;
            double dcL=dc?meanL:0,dcR=dc?meanR:0;
            double bandDb=10*Math.log10(Math.max(1e-15,m.lowMid)/Math.max(1e-15,m.upperMid));
            double crest=db(m.peak)-db(m.rms());
            // Only a pronounced low-mid excess on an uncompressed recording is eligible.
            // This tiny subtractive correction is intentionally disabled on pre-limited material.
            double eq=(bandDb>17.0 && crest>=9.0 && m.frames>=reader.sampleRate*10L
                   && m.rms()>=.012 && m.monoLossDb()>-3.0)?-.50:0;
            String decision;
            if(eq<0)decision="Strong low-mid dominance: conservative -0.50 dB cut at 280 Hz; compare at equal loudness.";
            else decision="No reliable tonal defect identified; tonal balance preserved.";
            if(crest<8.5)decision+=" Audio appears relatively dense/limited; no new dynamics processing.";
            if(m.monoLossDb()<-3.0)decision+=" Mono fold-down risk: flagged only; stereo unchanged.";
            notify(cb,35,"Checking output peak");
            double processedPeak=render(reader,null,dcL,dcR,eq,1.0,cb,35,59);
            double gain=processedPeak>HEADROOM?HEADROOM/processedPeak:1.0;
            if(!Double.isFinite(gain)||gain<=0)throw new IOException("Cannot set safe output gain");
            notify(cb,60,"Exporting 24-bit WAV");
            try(AutoMixEngine.WavWriter out=new AutoMixEngine.WavWriter(stage,reader.sampleRate,reader.frames)){
                render(reader,out,dcL,dcR,eq,gain,cb,60,97);
            }
            try{
                Files.move(stage.toPath(),output.toPath(),StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            }catch(AtomicMoveNotSupportedException unsupported){
                Files.move(stage.toPath(),output.toPath(),StandardCopyOption.REPLACE_EXISTING);
            }
            try(AutoMixEngine.WavReader verify=new AutoMixEngine.WavReader(output)){
                if(verify.frames!=reader.frames||verify.channels!=2||verify.bits!=24
                   ||verify.sampleRate!=reader.sampleRate)
                    throw new IOException("Finished WAV format mismatch");
            }
            notify(cb,100,"Stereo premaster ready");
            return new Report(output,reader.frames,reader.sampleRate,reader.bits,
                m,bandDb,dc,eq,db(gain),processedPeak*gain,decision);
        }finally{
            Files.deleteIfExists(stage.toPath());
        }
    }
}
