package com.dennis.automix.audio;

import java.io.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;

/**
 * Pure-Java offline block-streaming WAV mixer. No Android APIs and no mastering stage.
 * Float processing prevents internal clipping; final premaster is 24-bit PCM.
 * The program refuses unknown or misaligned inputs rather than guessing.
 */
public final class AutoMixEngine {
    private static final int BLOCK = 4096;
    private static final double TARGET_PEAK = Math.pow(10.0, -6.0 / 20.0);
    private static final double MAX_STEM_GAIN_DB = 4.0;
    private AutoMixEngine() {}

    public interface Progress {
        void report(int percentage, String message);
    }
    public static final class Track {
        public final String name;
        public final File file;
        public Track(String name, File file) { this.name = name; this.file = file; }
    }
    public static final class Analysis {
        public final String name, role;
        public final double peak, activeRms;
        public final long frames;
        public final int channels;
        public final double gainDb;
        public Analysis(String name, String role, double peak, double activeRms,
                        long frames, int channels, double gainDb) {
            this.name=name; this.role=role; this.peak=peak;
            this.activeRms=activeRms; this.frames=frames;
            this.channels=channels; this.gainDb=gainDb;
        }
    }
    public static final class Result {
        public final File output;
        public final int sampleRate;
        public final long frames;
        public final double preScalePeak, peakDbFs, outputGainDb;
        public final List<Analysis> tracks;
        Result(File output, int rate, long frames, double preScalePeak, double peakDbFs,
               double outputGainDb, List<Analysis> tracks) {
            this.output=output; sampleRate=rate; this.frames=frames;
            this.preScalePeak=preScalePeak; this.peakDbFs=peakDbFs;
            this.outputGainDb=outputGainDb;
            this.tracks=Collections.unmodifiableList(new ArrayList<>(tracks));
        }
    }
    static final class Stats {
        double peak, sumSq, activeSq;
        long samples, activeSamples, frames;
        int channels;
        double rms() {
            if(activeSamples>0) return Math.sqrt(activeSq/activeSamples);
            return samples==0?0:Math.sqrt(sumSq/samples);
        }
    }
    public static Result mix(List<Track> inputs, File output, Progress callback) throws IOException {
        if (inputs == null || inputs.size() < 2 || inputs.size() > 4)
            throw new IOException("Select between 2 and 4 WAV tracks.");
        if(output==null) throw new IOException("Output location required.");
        List<WavReader> readers = new ArrayList<>();
        File unfinished=new File(output.getAbsolutePath()+".part");
        boolean success=false;
        try {
            int rate=-1;
            long maxFrames=0, minFrames=Long.MAX_VALUE;
            for(Track track:inputs) {
                WavReader r=new WavReader(track.file);
                readers.add(r);
                if(rate<0) rate=r.sampleRate;
                if(r.sampleRate != rate)
                    throw new IOException("Different sample rates. Supply WAVs at a common sample rate (e.g. 48 kHz).");
                maxFrames=Math.max(maxFrames,r.frames);
                minFrames=Math.min(minFrames,r.frames);
                if(r.frames<=0) throw new IOException("Empty WAV: "+track.name);
            }
            // Differences under 50 ms are zero-padded. Larger gaps may mean wrong time alignment.
            if(maxFrames-minFrames>rate*0.05)
                throw new IOException("Tracks have different lengths (>50 ms). Export start-aligned stems of equal duration.");
            if(maxFrames>0xFFFFFFFFL/6L-36L)
                throw new IOException("Output exceeds classic WAV 4 GB limit. Divide the project into sections.");
            if(callback!=null) callback.report(3,"Inspecting WAVs");
            List<Stats> stats=new ArrayList<>();
            for(int i=0;i<readers.size();i++) {
                checkCancelled();
                stats.add(scan(readers.get(i)));
                if(callback!=null) callback.report(5+(i+1)*8,"Analysed "+inputs.get(i).name);
            }
            double[] rms=new double[stats.size()];
            for(int i=0;i<stats.size();i++) rms[i]=stats.get(i).rms();
            double[] nonzero=Arrays.stream(rms).filter(x->x>0.0000001).sorted().toArray();
            if(nonzero.length==0) throw new IOException("All tracks are silent.");
            double median=nonzero[nonzero.length/2];
            if(nonzero.length%2==0)
                median=(nonzero[nonzero.length/2]+nonzero[nonzero.length/2-1])*0.5;
            double[] gains=new double[stats.size()];
            List<Analysis> analyses=new ArrayList<>();
            boolean twoTrack=inputs.size()==2;
            for(int i=0;i<stats.size();i++){
                String role=role(inputs.get(i).name);
                double target=median*roleWeight(role,twoTrack);
                double g=rms[i]<0.0000001?1.0:target/rms[i];
                double dB=clamp(20*Math.log10(Math.max(0.0000001,g)),
                                -MAX_STEM_GAIN_DB, MAX_STEM_GAIN_DB);
                if(rms[i]<0.0000001) dB=0;
                gains[i]=Math.pow(10.0,dB/20.0);
                Stats s=stats.get(i);
                analyses.add(new Analysis(inputs.get(i).name,role,s.peak,rms[i],
                        s.frames,s.channels,dB));
            }
            double peak=render(readers,gains,analyses,rate,maxFrames,null,1.0,
                    callback,40,68);
            // Never amplify the premaster just to meet an arbitrary peak target.
            double scale=peak>TARGET_PEAK?TARGET_PEAK/peak:1.0;
            if(callback!=null) callback.report(70,"Writing 24-bit premaster");
            File parent=output.getAbsoluteFile().getParentFile();
            if(parent!=null && !parent.exists() && !parent.mkdirs())
                throw new IOException("Unable to create output folder.");
            try(WavWriter writer=new WavWriter(unfinished,rate,maxFrames)){
                render(readers,gains,analyses,rate,maxFrames,writer,scale,
                        callback,72,98);
            }
            try {
                Files.move(unfinished.toPath(),output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            } catch(AtomicMoveNotSupportedException unsupported) {
                Files.move(unfinished.toPath(),output.toPath(),StandardCopyOption.REPLACE_EXISTING);
            }
            success=true;
            if(callback!=null) callback.report(100,"Premaster finished");
            return new Result(output,rate,maxFrames,peak,
                    20*Math.log10(Math.max(0.000000000001,peak*scale)),
                    20*Math.log10(scale),analyses);
        } finally {
            for(WavReader r:readers) try { r.close(); } catch(IOException ignored){}
            if(!success) unfinished.delete();
        }
    }
    private static Stats scan(WavReader reader) throws IOException {
        Stats s=new Stats();
        s.frames=reader.frames; s.channels=reader.channels;
        reader.rewind();
        float[] b=new float[BLOCK*reader.channels];
        int count;
        while((count=reader.read(b,BLOCK))>0){
            checkCancelled();
            double blockSq=0;
            for(int i=0;i<count*reader.channels;i++){
                float x=b[i];
                if(!Float.isFinite(x)) throw new IOException("WAV contains NaN or infinity.");
                s.peak=Math.max(s.peak,Math.abs((double)x));
                blockSq+=x*(double)x;
            }
            int samples=count*reader.channels;
            s.sumSq+=blockSq; s.samples+=samples;
            if(Math.sqrt(blockSq/samples)>=0.0005){
                s.activeSq+=blockSq; s.activeSamples+=samples;
            }
        }
        return s;
    }
    private static double render(List<WavReader> readers,double[] gains,
                                 List<Analysis> analyses,int rate,long total,
                                 WavWriter out,double scale,Progress progress,
                                 int start,int end) throws IOException {
        int n=readers.size();
        float[][] buffer=new float[n][BLOCK*2];
        HighPass[][] filters=new HighPass[n][2];
        for(int t=0;t<n;t++){
            readers.get(t).rewind();
            double cutoff="vocals".equals(analyses.get(t).role)?38:24;
            for(int c=0;c<2;c++) filters[t][c]=new HighPass(rate,cutoff);
        }
        double peak=0;
        float[] mix=new float[BLOCK*2];
        long frame=0;
        int lastPercent=-1;
        while(frame<total){
            checkCancelled();
            int count=(int)Math.min(BLOCK,total-frame);
            int[] got=new int[n];
            for(int t=0;t<n;t++)got[t]=readers.get(t).read(buffer[t],count);
            for(int f=0;f<count;f++){
                double left=0,right=0;
                for(int t=0;t<n;t++){
                    if(f>=got[t])continue; // Zero-pad tiny differences.
                    WavReader r=readers.get(t);
                    double a=buffer[t][f*r.channels]*gains[t];
                    double b=r.channels==1?a:buffer[t][f*2+1]*gains[t];
                    left+=filters[t][0].process(a);
                    right+=filters[t][1].process(b);
                }
                peak=Math.max(peak,Math.max(Math.abs(left),Math.abs(right)));
                if(out!=null){
                    mix[f*2]=(float)(left*scale);
                    mix[f*2+1]=(float)(right*scale);
                }
            }
            if(out!=null)out.write(mix,count);
            frame+=count;
            int pct=start+(int)((end-start)*(double)frame/total);
            if(progress!=null && pct!=lastPercent){
                progress.report(pct,out==null?"Checking mix peak":"Rendering premaster");
                lastPercent=pct;
            }
        }
        return peak;
    }
    private static String role(String name) {
        String s=name.toLowerCase(Locale.ROOT);
        if(s.contains("vocal")||s.contains("voice")||s.contains("vox"))return "vocals";
        if(s.contains("drum")||s.contains("kick")||s.contains("snare")||s.contains("perc"))return "drums";
        if(s.contains("bass")||s.contains("sub"))return "bass";
        if(s.contains("instrumental")||s.contains("backing"))return "instrumental";
        return "other";
    }
    private static double roleWeight(String role,boolean twoTrack) {
        switch(role) {
            case "vocals":return twoTrack?1.13:1.18;
            case "drums":return 1.02;
            case "bass":return 0.94;
            case "instrumental":return 1.0;
            default:return twoTrack?1.0:0.91;
        }
    }
    private static double clamp(double x,double a,double b){return Math.max(a,Math.min(b,x));}
    private static void checkCancelled() throws IOException {
        if(Thread.currentThread().isInterrupted())throw new IOException("Processing interrupted.");
    }
    private static final class HighPass {
        final double b0,b1,b2,a1,a2;
        double x1,x2,y1,y2;
        HighPass(int rate,double hz) {
            double q=0.70710678118, omega=2*Math.PI*hz/rate, cos=Math.cos(omega);
            double alpha=Math.sin(omega)/(2*q), a0=1+alpha;
            b0=(1+cos)/2/a0;
            b1=-(1+cos)/a0;
            b2=b0;
            a1=-2*cos/a0;
            a2=(1-alpha)/a0;
        }
        double process(double x){
            double y=b0*x+b1*x1+b2*x2-a1*y1-a2*y2;
            x2=x1;x1=x;y2=y1;y1=y;
            return y;
        }
    }
    public static final class WavReader implements Closeable {
        final RandomAccessFile raf;
        public final int channels,sampleRate,bits,format;
        public final long frames;
        final long dataStart;
        final int blockAlign;
        final byte[] scratch=new byte[BLOCK*8];
        private long remaining;
        public WavReader(File file) throws IOException {
            raf=new RandomAccessFile(file,"r");
            try {
                if(raf.length()<44||!"RIFF".equals(readTag(raf)))throw new IOException("Unsupported WAV (RIFF required): "+file.getName());
                readU32(raf);
                if(!"WAVE".equals(readTag(raf)))throw new IOException("Not a WAVE file: "+file.getName());
                int channelsFound=0,rateFound=0,bitsFound=0,formatFound=0,alignFound=0;
                long offset=-1,size=-1;
                while(raf.getFilePointer()+8<=raf.length()){
                    String tag=readTag(raf);
                    long len=readU32(raf);
                    long pos=raf.getFilePointer();
                    if(len>raf.length()-pos)throw new IOException("Truncated WAV chunk: "+file.getName());
                    if("fmt ".equals(tag)){
                        if(len<16||len>65536)throw new IOException("Invalid WAV fmt chunk.");
                        byte[] fmt=new byte[(int)len];
                        raf.readFully(fmt);
                        formatFound=u16(fmt,0);
                        channelsFound=u16(fmt,2);
                        rateFound=(int)u32(fmt,4);
                        alignFound=u16(fmt,12);
                        bitsFound=u16(fmt,14);
                        if(formatFound==65534){
                            if(len<40)throw new IOException("Invalid extensible WAV.");
                            formatFound=u16(fmt,24);
                        }
                    } else if("data".equals(tag)){
                        offset=pos;size=len;
                    }
                    long next=pos+len+(len%2);
                    if(next>raf.length())next=raf.length();
                    raf.seek(next);
                    if(offset>=0&&channelsFound>0)break;
                }
                if(offset<0||rateFound<8000||rateFound>192000||
                   channelsFound<1||channelsFound>2||
                   !(bitsFound==16||bitsFound==24||bitsFound==32)||
                   !(formatFound==1||formatFound==3)||
                   (formatFound==3&&bitsFound!=32)||
                   alignFound!=channelsFound*(bitsFound/8)||
                   size%alignFound!=0)
                    throw new IOException("Unsupported format: PCM 16/24/32 or float32, mono/stereo WAV required: "+file.getName());
                channels=channelsFound;sampleRate=rateFound;bits=bitsFound;format=formatFound;
                blockAlign=alignFound;dataStart=offset;frames=size/blockAlign;
                remaining=frames;
                raf.seek(dataStart);
            } catch(IOException|RuntimeException e){raf.close();throw e;}
        }
        public void rewind() throws IOException {raf.seek(dataStart);remaining=frames;}
        public int read(float[] out,int maxFrames) throws IOException {
            int n=(int)Math.min(Math.min(maxFrames,BLOCK),remaining);
            if(n<=0)return 0;
            int size=n*blockAlign;
            raf.readFully(scratch,0,size);
            int p=0;
            for(int i=0;i<n*channels;i++){
                float v;
                if(bits==16){
                    int sample=(short)((scratch[p]&255)|((scratch[p+1]&255)<<8));
                    v=sample/32768.0f; p+=2;
                }else if(bits==24){
                    int sample=(scratch[p]&255)|((scratch[p+1]&255)<<8)|(scratch[p+2]<<16);
                    v=sample/8388608.0f;p+=3;
                }else{
                    int raw=(scratch[p]&255)|((scratch[p+1]&255)<<8)|
                            ((scratch[p+2]&255)<<16)|(scratch[p+3]<<24);
                    v=format==3?Float.intBitsToFloat(raw):(float)(raw/2147483648.0);
                    p+=4;
                }
                out[i]=v;
            }
            remaining-=n;
            return n;
        }
        public void close()throws IOException{raf.close();}
    }
    public static final class WavWriter implements Closeable {
        private final RandomAccessFile raf;
        private long remaining;
        public WavWriter(File file,int rate,long frames)throws IOException{
            if(frames<0||frames*6L>0xFFFFFFFFL-36L)
                throw new IOException("24-bit WAV exceeds 4GB RIFF capacity.");
            raf=new RandomAccessFile(file,"rw");
            raf.setLength(0);
            remaining=frames;
            int bytes=(int)(frames*6L);
            tag(raf,"RIFF");u32(raf,36L+(bytes&0xffffffffL));tag(raf,"WAVE");
            tag(raf,"fmt ");u32(raf,16);u16(raf,1);u16(raf,2);
            u32(raf,rate);u32(raf,rate*6L);u16(raf,6);u16(raf,24);
            tag(raf,"data");u32(raf,bytes&0xffffffffL);
        }
        public void write(float[] interleaved,int frames)throws IOException{
            if(frames<0||frames>remaining)throw new IOException("WAV writer frame mismatch.");
            byte[] bytes=new byte[frames*6];
            for(int i=0;i<frames*2;i++){
                float f=interleaved[i];
                if(!Float.isFinite(f))throw new IOException("Non-finite output sample.");
                long q=Math.round(Math.max(-1.0,Math.min(8388607.0/8388608,f))*8388608.0);
                int p=i*3;
                bytes[p]=(byte)q;bytes[p+1]=(byte)(q>>8);bytes[p+2]=(byte)(q>>16);
            }
            raf.write(bytes);
            remaining-=frames;
        }
        public void close()throws IOException{
            try{if(remaining!=0)throw new IOException("Incomplete WAV render.");}
            finally{raf.close();}
        }
    }
    private static String readTag(RandomAccessFile f)throws IOException {
        byte[] b=new byte[4];f.readFully(b);return new String(b,StandardCharsets.US_ASCII);
    }
    private static long readU32(RandomAccessFile f)throws IOException {
        long a=f.readUnsignedByte(),b=f.readUnsignedByte(),
             c=f.readUnsignedByte(),d=f.readUnsignedByte();
        return a|(b<<8)|(c<<16)|(d<<24);
    }
    private static void tag(RandomAccessFile f,String s)throws IOException {
        f.write(s.getBytes(StandardCharsets.US_ASCII));
    }
    private static void u16(RandomAccessFile f,int x)throws IOException {
        f.write(x&255);f.write((x>>>8)&255);
    }
    private static void u32(RandomAccessFile f,long x)throws IOException {
        for(int i=0;i<4;i++)f.write((int)(x>>>(i*8))&255);
    }
    private static int u16(byte[] b,int p) {
        return (b[p]&255)|((b[p+1]&255)<<8);
    }
    private static long u32(byte[] b,int p){
        return ((long)b[p]&255)|(((long)b[p+1]&255)<<8)|
               (((long)b[p+2]&255)<<16)|(((long)b[p+3]&255)<<24);
    }
}
